//! Batched dig transactions.
//!
//! Instruction order inside one transaction:
//!
//! ```text
//! [ComputeBudget limit, ComputeBudget price]   (legacy / v0 only; v1 carries both in its config)
//! [ORE checkpoint ...]                          one per rig whose miner still owes a checkpoint
//! [Secp256r1SigVerify ...]                      up to 8 heartbeats each, 32-byte digest messages
//! heads_down::dig                               entries point at (precompile ix, entry)
//! ```
//!
//! Packing is exact rather than estimated: every candidate batch is compiled into the real
//! message (with the crank's lookup tables for v0) and serialized with `wincode`, then
//! checked against the wire limit (1232 bytes legacy/v0, 4096 bytes v1), the account-lock
//! limit, the v1 address limit, the compute ceiling and the `u8` precompile index.

use solana_address::Address;
use solana_hash::Hash;
use solana_instruction::Instruction;
use solana_keypair::Keypair;
use solana_message::{v0, v1, AddressLookupTableAccount, VersionedMessage};
use solana_signature::Signature;
use solana_transaction::versioned::VersionedTransaction;

use crate::hd::{self, DigEntry, RigAccounts};
use crate::heartbeat::VerifiedHeartbeat;
use crate::ore;

/// `ComputeBudget111111111111111111111111111111`.
pub const COMPUTE_BUDGET_PROGRAM_ID: Address =
    Address::from_str_const("ComputeBudget111111111111111111111111111111");
/// Legacy / v0 wire limit (`PACKET_DATA_SIZE`).
pub const PACKET_DATA_SIZE: usize = 1232;
/// v1 wire limit.
pub const V1_MAX_TRANSACTION_SIZE: usize = v1::MAX_TRANSACTION_SIZE;
/// v1 address limit.
pub const V1_MAX_ADDRESSES: usize = v1::MAX_ADDRESSES as usize;
/// Per-transaction compute ceiling.
pub const MAX_COMPUTE_UNITS: u32 = 1_400_000;
/// Default account-lock limit per transaction.
pub const DEFAULT_MAX_ACCOUNT_LOCKS: usize = 64;
/// Max signatures per Secp256r1SigVerify instruction (Agave).
pub const MAX_SIGS_PER_PRECOMPILE: usize = p256_introspect::MAX_SIGNATURES_PER_INSTRUCTION as usize;

/// Transaction format.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "lowercase")]
pub enum TxFormat {
    /// Legacy message.
    Legacy,
    /// v0 message with the crank's lookup tables.
    V0,
    /// v1 message (4096 bytes, config instead of ComputeBudget instructions, no lookup tables).
    V1,
}

impl TxFormat {
    /// Wire limit.
    pub fn size_limit(self) -> usize {
        match self {
            TxFormat::Legacy | TxFormat::V0 => PACKET_DATA_SIZE,
            TxFormat::V1 => V1_MAX_TRANSACTION_SIZE,
        }
    }
}

/// `SetComputeUnitLimit` (tag 2, u32).
pub fn set_compute_unit_limit(units: u32) -> Instruction {
    let mut data = vec![2u8];
    data.extend_from_slice(&units.to_le_bytes());
    Instruction { program_id: COMPUTE_BUDGET_PROGRAM_ID, accounts: vec![], data }
}

/// `SetComputeUnitPrice` (tag 3, u64 micro-lamports per CU).
pub fn set_compute_unit_price(micro_lamports: u64) -> Instruction {
    let mut data = vec![3u8];
    data.extend_from_slice(&micro_lamports.to_le_bytes());
    Instruction { program_id: COMPUTE_BUDGET_PROGRAM_ID, accounts: vec![], data }
}

/// Compute estimate used before (or instead of) simulation.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Deserialize, serde::Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct CuEstimate {
    /// Fixed overhead per transaction.
    pub base: u32,
    /// Per rig dug (verify + gate + tile pick + ORE deploy CPI + reimbursement).
    pub per_rig: u32,
    /// Per ORE checkpoint instruction.
    pub per_checkpoint: u32,
}

impl Default for CuEstimate {
    fn default() -> Self {
        // Spike 1(a): 26k CU per rig for a 5-tile ORE deploy through the executor PDA;
        // 15 tiles, the heartbeat check and the reimbursement add to it.
        CuEstimate { base: 10_000, per_rig: 60_000, per_checkpoint: 30_000 }
    }
}

impl CuEstimate {
    /// Estimated units for a batch, saturating.
    pub fn units(&self, rigs: usize, checkpoints: usize) -> u32 {
        let r = u32::try_from(rigs).unwrap_or(u32::MAX);
        let c = u32::try_from(checkpoints).unwrap_or(u32::MAX);
        self.base
            .saturating_add(self.per_rig.saturating_mul(r))
            .saturating_add(self.per_checkpoint.saturating_mul(c))
    }
}

/// One rig in a dig batch.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RigDig {
    /// The four per-rig accounts (authority always from the Rig account).
    pub accounts: RigAccounts,
    /// A fresh heartbeat to put on-chain, or `None` to reuse the rig's current lease.
    pub heartbeat: Option<VerifiedHeartbeat>,
    /// `Some(miner.round_id)` if an ORE checkpoint must precede the dig.
    pub checkpoint_round: Option<u64>,
}

/// Everything the builder needs besides the rigs.
#[derive(Clone, Debug)]
pub struct BuildParams {
    /// heads_down program id.
    pub program_id: Address,
    /// Fee payer and dig signer.
    pub cranker: Address,
    /// The `Board.round_id` this transaction must land in (selects the Round PDA).
    pub round_id: u64,
    /// Message format.
    pub format: TxFormat,
    /// Priority fee in micro-lamports per CU.
    pub cu_price_micro_lamports: u64,
    /// Explicit CU limit (e.g. from simulation), or `None` to use `cu_estimate`.
    pub cu_limit: Option<u32>,
    /// Estimate used when `cu_limit` is `None`.
    pub cu_estimate: CuEstimate,
    /// v1 `loaded_accounts_data_size_limit` (bytes).
    pub loaded_accounts_data_size_limit: u32,
    /// Account-lock limit enforced by packing.
    pub max_account_locks: usize,
    /// Hard cap on rigs per transaction.
    pub max_rigs_per_tx: usize,
    /// Optional tip transfer appended last (Helius Sender requires one): `(recipient, lamports)`.
    pub tip: Option<(Address, u64)>,
}

/// System `Transfer` (tag 2, u64) from `from` to `to`.
pub fn system_transfer(from: &Address, to: &Address, lamports: u64) -> Instruction {
    let mut data = 2u32.to_le_bytes().to_vec();
    data.extend_from_slice(&lamports.to_le_bytes());
    Instruction {
        program_id: ore::SYSTEM_PROGRAM_ID,
        accounts: vec![
            solana_instruction::AccountMeta::new(*from, true),
            solana_instruction::AccountMeta::new(*to, false),
        ],
        data,
    }
}

/// Transaction build failures.
#[derive(Debug, thiserror::Error)]
pub enum TxError {
    /// No rigs.
    #[error("empty batch")]
    Empty,
    /// Precompile index would exceed `u8` (or hit the 0xFF sentinel).
    #[error("too many instructions for a u8 hb_ix")]
    TooManyInstructions,
    /// dig instruction rejected.
    #[error(transparent)]
    Dig(#[from] hd::DigBuildError),
    /// Precompile data rejected.
    #[error("precompile data: {0:?}")]
    Precompile(p256_introspect::client::ClientError),
    /// Message compilation failed.
    #[error("compile: {0}")]
    Compile(String),
    /// Signing failed.
    #[error("sign: {0}")]
    Sign(String),
    /// Serialization failed.
    #[error("serialize: {0}")]
    Serialize(String),
}

/// The compute limit a batch will declare.
pub fn cu_limit_for(p: &BuildParams, rigs: &[RigDig]) -> u32 {
    p.cu_limit
        .unwrap_or_else(|| p.cu_estimate.units(rigs.len(), rigs.iter().filter(|r| r.checkpoint_round.is_some()).count()))
        .min(MAX_COMPUTE_UNITS)
}

/// Priority fee in lamports: `ceil(cu_limit * price / 1e6)`.
pub fn priority_fee_lamports(cu_limit: u32, micro_lamports: u64) -> u64 {
    let micro = u128::from(cu_limit).saturating_mul(u128::from(micro_lamports));
    u64::try_from(micro.div_ceil(1_000_000)).unwrap_or(u64::MAX)
}

/// Build the instruction list for one batch.
pub fn build_instructions(p: &BuildParams, rigs: &[RigDig]) -> Result<Vec<Instruction>, TxError> {
    if rigs.is_empty() {
        return Err(TxError::Empty);
    }
    let mut ixs = Vec::new();
    if p.format != TxFormat::V1 {
        ixs.push(set_compute_unit_limit(cu_limit_for(p, rigs)));
        ixs.push(set_compute_unit_price(p.cu_price_micro_lamports));
    }
    for r in rigs {
        if let Some(round) = r.checkpoint_round {
            ixs.push(ore::checkpoint_ix(&p.cranker, &r.accounts.authority, round));
        }
    }
    // Heartbeats, grouped by 8 per precompile instruction, in rig order.
    let mut entries: Vec<DigEntry> = vec![DigEntry::reuse_lease(); rigs.len()];
    let with_hb: Vec<(usize, &VerifiedHeartbeat)> =
        rigs.iter().enumerate().filter_map(|(i, r)| r.heartbeat.as_ref().map(|h| (i, h))).collect();
    for chunk in with_hb.chunks(MAX_SIGS_PER_PRECOMPILE) {
        let ix_index = u8::try_from(ixs.len()).map_err(|_| TxError::TooManyInstructions)?;
        if ix_index == hd::HB_REUSE_LEASE {
            return Err(TxError::TooManyInstructions);
        }
        let inputs: Vec<p256_introspect::client::SignatureInput<'_>> = chunk
            .iter()
            .map(|(_, h)| p256_introspect::client::SignatureInput {
                signature: h.sig,
                public_key: h.pubkey,
                message: &h.digest,
            })
            .collect();
        let data = p256_introspect::client::build_instruction_data(&inputs).map_err(TxError::Precompile)?;
        ixs.push(Instruction { program_id: hd::SECP256R1_PROGRAM_ID, accounts: vec![], data });
        for (sig_index, (rig_i, h)) in chunk.iter().enumerate() {
            entries[*rig_i] = DigEntry {
                hb_ix: ix_index,
                hb_sig_index: u8::try_from(sig_index).map_err(|_| TxError::TooManyInstructions)?,
                counter: h.fields.counter,
                round_id: h.fields.round_id,
                lease_rounds: h.fields.lease_rounds,
            };
        }
    }
    let pairs: Vec<(RigAccounts, DigEntry)> = rigs.iter().map(|r| r.accounts).zip(entries).collect();
    ixs.push(hd::dig_ix(&p.program_id, &p.cranker, &ore::round_pda(p.round_id), &pairs)?);
    // Last, so it never shifts a precompile index.
    if let Some((to, lamports)) = p.tip {
        ixs.push(system_transfer(&p.cranker, &to, lamports));
    }
    Ok(ixs)
}

/// Compile a message for `ixs`.
pub fn compile_message(
    p: &BuildParams,
    ixs: &[Instruction],
    blockhash: Hash,
    alts: &[AddressLookupTableAccount],
    cu_limit: u32,
) -> Result<VersionedMessage, TxError> {
    Ok(match p.format {
        TxFormat::Legacy => {
            VersionedMessage::Legacy(solana_message::Message::new_with_blockhash(ixs, Some(&p.cranker), &blockhash))
        }
        TxFormat::V0 => VersionedMessage::V0(
            v0::Message::try_compile(&p.cranker, ixs, alts, blockhash).map_err(|e| TxError::Compile(e.to_string()))?,
        ),
        TxFormat::V1 => {
            let cfg = v1::TransactionConfig::empty()
                .with_compute_unit_limit(cu_limit)
                .with_priority_fee(priority_fee_lamports(cu_limit, p.cu_price_micro_lamports))
                .with_loaded_accounts_data_size_limit(p.loaded_accounts_data_size_limit);
            VersionedMessage::V1(
                v1::Message::try_compile_with_config(&p.cranker, ixs, blockhash, cfg)
                    .map_err(|e| TxError::Compile(e.to_string()))?,
            )
        }
    })
}

/// Number of distinct accounts the message locks (static + looked up).
pub fn account_count(msg: &VersionedMessage) -> usize {
    match msg {
        VersionedMessage::Legacy(m) => m.account_keys.len(),
        VersionedMessage::V0(m) => {
            m.account_keys.len()
                + m.address_table_lookups
                    .iter()
                    .map(|l| l.writable_indexes.len() + l.readonly_indexes.len())
                    .sum::<usize>()
        }
        VersionedMessage::V1(m) => m.account_keys.len(),
    }
}

/// A transaction with placeholder signatures (for sizing) or signed by `signer`.
pub fn make_transaction(msg: VersionedMessage, signer: Option<&Keypair>) -> Result<VersionedTransaction, TxError> {
    match signer {
        Some(kp) => VersionedTransaction::try_new(msg, &[kp]).map_err(|e| TxError::Sign(e.to_string())),
        None => {
            let n = usize::from(msg.header().num_required_signatures);
            Ok(VersionedTransaction { signatures: vec![Signature::default(); n], message: msg })
        }
    }
}

/// Wire bytes of a transaction.
pub fn serialize(tx: &VersionedTransaction) -> Result<Vec<u8>, TxError> {
    wincode::serialize(tx).map_err(|e| TxError::Serialize(e.to_string()))
}

/// A batch that fits, with its measured shape.
#[derive(Clone, Debug)]
pub struct Batch {
    /// Rigs in dig order.
    pub rigs: Vec<RigDig>,
    /// Serialized size with placeholder signatures.
    pub wire_size: usize,
    /// Accounts locked.
    pub accounts: usize,
    /// Declared compute limit.
    pub cu_limit: u32,
    /// Secp256r1 signatures carried (each one costs an extra signature fee).
    pub precompile_signatures: usize,
}

/// Why a batch does not fit.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Misfit {
    /// Over the wire limit.
    Size(usize),
    /// Over the account-lock / v1 address limit.
    Accounts(usize),
    /// Over the rig cap.
    Rigs,
    /// Over the compute ceiling.
    Compute,
    /// Other builder error (e.g. too many instructions).
    Build,
}

/// Compile `ixs` for `p.format` and check the account and wire limits: `(wire_size, accounts)`.
pub fn measure_instructions(
    p: &BuildParams,
    ixs: &[Instruction],
    alts: &[AddressLookupTableAccount],
    cu_limit: u32,
) -> Result<(usize, usize), Misfit> {
    let msg = compile_message(p, ixs, Hash::default(), alts, cu_limit).map_err(|_| Misfit::Build)?;
    let accounts = account_count(&msg);
    let acct_limit = match p.format {
        TxFormat::V1 => p.max_account_locks.min(V1_MAX_ADDRESSES),
        _ => p.max_account_locks,
    };
    if accounts > acct_limit {
        return Err(Misfit::Accounts(accounts));
    }
    let tx = make_transaction(msg, None).map_err(|_| Misfit::Build)?;
    let wire_size = serialize(&tx).map_err(|_| Misfit::Build)?.len();
    if wire_size > p.format.size_limit() {
        return Err(Misfit::Size(wire_size));
    }
    Ok((wire_size, accounts))
}

/// Measure one candidate batch.
pub fn measure(p: &BuildParams, rigs: &[RigDig], alts: &[AddressLookupTableAccount]) -> Result<Batch, Misfit> {
    if rigs.len() > p.max_rigs_per_tx {
        return Err(Misfit::Rigs);
    }
    let est = p.cu_estimate.units(rigs.len(), rigs.iter().filter(|r| r.checkpoint_round.is_some()).count());
    if p.cu_limit.is_none() && est > MAX_COMPUTE_UNITS {
        return Err(Misfit::Compute);
    }
    let cu_limit = cu_limit_for(p, rigs);
    let ixs = build_instructions(p, rigs).map_err(|_| Misfit::Build)?;
    let (wire_size, accounts) = measure_instructions(p, &ixs, alts, cu_limit)?;
    Ok(Batch {
        rigs: rigs.to_vec(),
        wire_size,
        accounts,
        cu_limit,
        precompile_signatures: rigs.iter().filter(|r| r.heartbeat.is_some()).count(),
    })
}

/// Greedy packing in the given order (the planner orders rigs by priority). A rig that does
/// not fit even alone is returned in the second vector.
pub fn pack(p: &BuildParams, rigs: &[RigDig], alts: &[AddressLookupTableAccount]) -> (Vec<Batch>, Vec<(RigDig, Misfit)>) {
    let mut out = Vec::new();
    let mut rejected = Vec::new();
    let mut current: Vec<RigDig> = Vec::new();
    let mut current_batch: Option<Batch> = None;
    for r in rigs {
        let mut candidate = current.clone();
        candidate.push(*r);
        match measure(p, &candidate, alts) {
            Ok(b) => {
                current = candidate;
                current_batch = Some(b);
            }
            Err(_) if !current.is_empty() => {
                if let Some(b) = current_batch.take() {
                    out.push(b);
                }
                current.clear();
                match measure(p, std::slice::from_ref(r), alts) {
                    Ok(b) => {
                        current.push(*r);
                        current_batch = Some(b);
                    }
                    Err(m) => rejected.push((*r, m)),
                }
            }
            Err(m) => rejected.push((*r, m)),
        }
    }
    if let Some(b) = current_batch {
        out.push(b);
    }
    (out, rejected)
}

/// Build and sign the final transaction for a packed batch.
pub fn sign_batch(
    p: &BuildParams,
    rigs: &[RigDig],
    alts: &[AddressLookupTableAccount],
    blockhash: Hash,
    signer: &Keypair,
) -> Result<VersionedTransaction, TxError> {
    let cu_limit = cu_limit_for(p, rigs);
    let ixs = build_instructions(p, rigs)?;
    let msg = compile_message(p, &ixs, blockhash, alts, cu_limit)?;
    make_transaction(msg, Some(signer))
}

/// Estimated lamports the cranker pays for a batch: one base signature, one signature per
/// secp256r1 entry (measured in `spikes/secp256r1`), plus the priority fee.
pub fn estimated_fee(batch: &Batch, cu_price_micro_lamports: u64, lamports_per_signature: u64) -> u64 {
    fee_for(batch.precompile_signatures, batch.cu_limit, cu_price_micro_lamports, lamports_per_signature)
}

/// Lamports per signature on Solana (the transaction signature and every secp256r1 entry).
pub const LAMPORTS_PER_SIGNATURE: u64 = 5_000;

/// `(1 + precompile_signatures) × lamports_per_signature + ceil(cu_limit × price / 10⁶)`.
pub fn fee_for(precompile_signatures: usize, cu_limit: u32, cu_price_micro_lamports: u64, lamports_per_signature: u64) -> u64 {
    let sigs = 1u64.saturating_add(precompile_signatures as u64);
    sigs.saturating_mul(lamports_per_signature)
        .saturating_add(priority_fee_lamports(cu_limit, cu_price_micro_lamports))
}

/// One Secp256r1SigVerify instruction carrying `entries` (≤ 8), each `(low-S sig, key, 32-byte digest)`.
pub fn precompile_ix(entries: &[([u8; 64], [u8; 33], [u8; 32])]) -> Result<Instruction, TxError> {
    let inputs: Vec<p256_introspect::client::SignatureInput<'_>> = entries
        .iter()
        .map(|(signature, public_key, message)| p256_introspect::client::SignatureInput {
            signature: *signature,
            public_key: *public_key,
            message,
        })
        .collect();
    let data = p256_introspect::client::build_instruction_data(&inputs).map_err(TxError::Precompile)?;
    Ok(Instruction { program_id: hd::SECP256R1_PROGRAM_ID, accounts: vec![], data })
}

// ---------------------------------------------------------------------------------------
// record_heartbeats batches.

/// One rig in a `record_heartbeats` batch.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RecordRig {
    /// Rig PDA.
    pub rig: Address,
    /// The verified heartbeat to put on-chain.
    pub heartbeat: VerifiedHeartbeat,
}

/// A `record_heartbeats` batch that fits.
#[derive(Clone, Debug)]
pub struct RecordBatch {
    /// Rigs in entry order.
    pub rigs: Vec<RecordRig>,
    /// Serialized size with placeholder signatures.
    pub wire_size: usize,
    /// Declared compute limit.
    pub cu_limit: u32,
}

/// Compute limit of a record batch: `base + per_rig × n` (the `per_checkpoint` term is unused).
pub fn record_cu_limit(est: &CuEstimate, rigs: usize) -> u32 {
    est.units(rigs, 0).min(MAX_COMPUTE_UNITS)
}

/// `[ComputeBudget limit, price]` (legacy / v0) `+ Secp256r1SigVerify (≤ 8 each) + record_heartbeats`.
/// Every entry names its precompile instruction by absolute index (never 0xFF).
pub fn build_record_instructions(p: &BuildParams, est: &CuEstimate, rigs: &[RecordRig]) -> Result<Vec<Instruction>, TxError> {
    if rigs.is_empty() {
        return Err(TxError::Empty);
    }
    let mut ixs = Vec::new();
    if p.format != TxFormat::V1 {
        ixs.push(set_compute_unit_limit(record_cu_limit(est, rigs.len())));
        ixs.push(set_compute_unit_price(p.cu_price_micro_lamports));
    }
    let mut entries = Vec::with_capacity(rigs.len());
    for chunk in rigs.chunks(MAX_SIGS_PER_PRECOMPILE) {
        let ix_index = u8::try_from(ixs.len()).map_err(|_| TxError::TooManyInstructions)?;
        if ix_index == hd::HB_REUSE_LEASE {
            return Err(TxError::TooManyInstructions);
        }
        let sigs: Vec<_> = chunk.iter().map(|r| (r.heartbeat.sig, r.heartbeat.pubkey, r.heartbeat.digest)).collect();
        ixs.push(precompile_ix(&sigs)?);
        for (i, r) in chunk.iter().enumerate() {
            let h = &r.heartbeat;
            entries.push((
                r.rig,
                DigEntry {
                    hb_ix: ix_index,
                    hb_sig_index: u8::try_from(i).map_err(|_| TxError::TooManyInstructions)?,
                    counter: h.fields.counter,
                    round_id: h.fields.round_id,
                    lease_rounds: h.fields.lease_rounds,
                },
            ));
        }
    }
    ixs.push(hd::record_heartbeats_ix(&p.program_id, &entries)?);
    if let Some((to, lamports)) = p.tip {
        ixs.push(system_transfer(&p.cranker, &to, lamports));
    }
    Ok(ixs)
}

/// Greedy packing of `record_heartbeats` batches (≤ 32 rigs per instruction, `p.max_rigs_per_tx`).
pub fn pack_records(
    p: &BuildParams,
    est: &CuEstimate,
    rigs: &[RecordRig],
    alts: &[AddressLookupTableAccount],
) -> (Vec<RecordBatch>, Vec<(RecordRig, Misfit)>) {
    let measure_one = |batch: &[RecordRig]| -> Result<RecordBatch, Misfit> {
        if batch.len() > p.max_rigs_per_tx.min(hd::MAX_RIGS_PER_IX) {
            return Err(Misfit::Rigs);
        }
        if est.units(batch.len(), 0) > MAX_COMPUTE_UNITS {
            return Err(Misfit::Compute);
        }
        let cu_limit = record_cu_limit(est, batch.len());
        let ixs = build_record_instructions(p, est, batch).map_err(|_| Misfit::Build)?;
        let (wire_size, _) = measure_instructions(p, &ixs, alts, cu_limit)?;
        Ok(RecordBatch { rigs: batch.to_vec(), wire_size, cu_limit })
    };
    let (mut out, mut rejected) = (Vec::new(), Vec::new());
    let mut current: Option<RecordBatch> = None;
    for r in rigs {
        let mut candidate = current.as_ref().map(|b| b.rigs.clone()).unwrap_or_default();
        candidate.push(*r);
        match measure_one(&candidate) {
            Ok(b) => current = Some(b),
            Err(_) if current.is_some() => {
                out.extend(current.take());
                match measure_one(std::slice::from_ref(r)) {
                    Ok(b) => current = Some(b),
                    Err(m) => rejected.push((*r, m)),
                }
            }
            Err(m) => rejected.push((*r, m)),
        }
    }
    out.extend(current);
    (out, rejected)
}

/// Build and sign a record batch.
pub fn sign_record_batch(
    p: &BuildParams,
    est: &CuEstimate,
    rigs: &[RecordRig],
    alts: &[AddressLookupTableAccount],
    blockhash: Hash,
    signer: &Keypair,
) -> Result<VersionedTransaction, TxError> {
    let ixs = build_record_instructions(p, est, rigs)?;
    let msg = compile_message(p, &ixs, blockhash, alts, record_cu_limit(est, rigs.len()))?;
    make_transaction(msg, Some(signer))
}

// ---------------------------------------------------------------------------------------
// Single-purpose legacy transactions: phone-signed BREAK / FREEZE, permissionless end_shift.

/// Top-level index of the precompile in [`signal_instructions`] (after the two ComputeBudget
/// instructions); `break_shift` / `freeze_rig` name it as `p256_ix`.
pub const SIGNAL_P256_IX: u8 = 2;

/// `[SetComputeUnitLimit, SetComputeUnitPrice, Secp256r1SigVerify (1 entry), break_shift |
/// freeze_rig (mode 1)]`: a phone-signed BREAK / FREEZE, landed by the crank as fee payer.
#[allow(clippy::too_many_arguments)]
pub fn signal_instructions(
    program_id: &Address,
    kind: hd::SignalKind,
    rig: &Address,
    authority: &Address,
    reason: u8,
    counter: u64,
    sig: [u8; 64],
    pubkey: [u8; 33],
    digest: [u8; 32],
    cu_limit: u32,
    cu_price_micro_lamports: u64,
) -> Result<Vec<Instruction>, TxError> {
    Ok(vec![
        set_compute_unit_limit(cu_limit),
        set_compute_unit_price(cu_price_micro_lamports),
        precompile_ix(&[(sig, pubkey, digest)])?,
        hd::signal_p256_ix(program_id, kind, rig, authority, reason, counter, SIGNAL_P256_IX, 0),
    ])
}

/// `[SetComputeUnitLimit, SetComputeUnitPrice, end_shift]` (the caller pays the ShiftLog rent).
pub fn end_shift_instructions(
    program_id: &Address,
    caller: &Address,
    rig: &Address,
    shift_id: u64,
    cu_limit: u32,
    cu_price_micro_lamports: u64,
) -> Vec<Instruction> {
    vec![
        set_compute_unit_limit(cu_limit),
        set_compute_unit_price(cu_price_micro_lamports),
        hd::end_shift_ix(program_id, caller, rig, shift_id),
    ]
}

/// Compile and sign a legacy transaction paid by `payer`.
pub fn sign_legacy(ixs: &[Instruction], payer: &Keypair, blockhash: Hash) -> Result<VersionedTransaction, TxError> {
    use solana_signer::Signer;
    let msg = VersionedMessage::Legacy(solana_message::Message::new_with_blockhash(ixs, Some(&payer.pubkey()), &blockhash));
    make_transaction(msg, Some(payer))
}
