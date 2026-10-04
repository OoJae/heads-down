//! The upgradeable loader's Buffer account, written at a pace a rate-limited RPC accepts.
//!
//! `solana program deploy --use-rpc` (Agave 4.1.2) sends every write of a deploy 10 ms apart
//! and never sends one again inside a blockhash window (its source: `client/src/
//! send_and_confirm_transactions_in_parallel.rs`). Against an RPC that allows one
//! `sendTransaction` a second, as Helius' free plan does, that leaves a handful of the roughly
//! 200 writes per signing round; this is read from the source, it was not run against Helius.
//! `write-buffer` does the same job slowly:
//!
//! 1. it creates the buffer exactly as that CLI would for the mode: owner, size, `Buffer` state
//!    with the deployer as authority, and the same lamports ([`buffer_rent_len`]);
//! 2. it writes only the chunks that differ from what the buffer already holds, one transaction
//!    every `1 / --rate` seconds, each signed on a recent blockhash with an explicit
//!    compute-unit limit and the priority price;
//! 3. it looks the sent writes up in batches (one `getBlockHeight` and one
//!    `getSignatureStatuses` per poll), signs a write again once its blockhash has expired, and
//!    takes "too many requests" or a timeout as "slow down", not as failure. A send that got no
//!    answer is watched like one that was taken, so the same chunk is not sent twice at once;
//! 4. it ends by reading the buffer back and comparing every byte with the file.
//!
//! It stops by itself when it cannot get anywhere: before the first write if the payer cannot
//! pay for the writes that are left, and after `Pace::stall` without one write seen to land
//! (writes that an RPC takes and that never land do not keep it going).
//!
//! Nothing is kept on disk: the buffer account is the only state, so the command can be killed
//! at any point and run again. `solana program deploy --buffer <keypair>` then finds every chunk
//! written, sends no write, and sends only its final transaction: `scripts/mainnet/dry-run.sh`
//! counts that on a local validator. The slow-down, timeout and expiry paths are tested against
//! a mock node (below); none of this has run against a rate-limited provider.

use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use anyhow::{anyhow, bail, Context, Result};
use hd_crank::ore::{BPF_UPGRADEABLE_LOADER_ID, SYSTEM_PROGRAM_ID};
use hd_crank::rpc::{redact_url, RpcError};
use hd_crank::tx;
use serde_json::{json, Value};
use solana_address::Address;
use solana_hash::Hash;
use solana_instruction::{AccountMeta, Instruction};
use solana_keypair::Keypair;
use solana_message::{Message, VersionedMessage};
use solana_signer::Signer;

use crate::cluster::{self, Cluster};
use crate::ops::{require_yes, so_info, write_json, DeployMode, BUFFER_HEADER_LEN, PROGRAMDATA_HEADER_LEN};
use crate::util::{credits_used_up, patiently, read_keypair, refused, retry_later, rfc3339, sign_tx, sol, tail, unix_now, Chain, Patience};

/// `UpgradeableLoaderInstruction::InitializeBuffer` (u32 tag).
const IX_INITIALIZE_BUFFER: u32 = 0;
/// `UpgradeableLoaderInstruction::Write` (u32 tag), then `offset: u32` and the bytes behind a
/// u64 length.
const IX_WRITE: u32 = 1;
/// `UpgradeableLoaderState::Buffer` (u32 tag), then the authority as `Option<Pubkey>`.
const STATE_BUFFER: u32 = 1;
/// The most signatures `getSignatureStatuses` takes, and so the most writes in flight.
const MAX_IN_FLIGHT: usize = 256;
/// Times the buffer is read back and what still differs is written again.
const PASSES: usize = 5;
/// Times a buffer that should exist is looked for again, one poll interval apart.
const NOT_YET_VISIBLE_TRIES: usize = 3;

// ---- what the loader expects ------------------------------------------------------------------

/// `[System CreateAccount, InitializeBuffer]`: the pair `solana program deploy` sends first. The
/// account is `37 + program_len` bytes, owned by the loader, with `authority` as its authority.
pub fn create_buffer_ixs(payer: &Address, buffer: &Address, authority: &Address, lamports: u64, program_len: u64) -> Vec<Instruction> {
    let mut create = 0u32.to_le_bytes().to_vec();
    create.extend_from_slice(&lamports.to_le_bytes());
    create.extend_from_slice(&(BUFFER_HEADER_LEN + program_len).to_le_bytes());
    create.extend_from_slice(BPF_UPGRADEABLE_LOADER_ID.as_ref());
    vec![
        Instruction {
            program_id: SYSTEM_PROGRAM_ID,
            accounts: vec![AccountMeta::new(*payer, true), AccountMeta::new(*buffer, true)],
            data: create,
        },
        Instruction {
            program_id: BPF_UPGRADEABLE_LOADER_ID,
            accounts: vec![AccountMeta::new(*buffer, false), AccountMeta::new_readonly(*authority, false)],
            data: IX_INITIALIZE_BUFFER.to_le_bytes().to_vec(),
        },
    ]
}

/// `Write { offset, bytes }`, signed by the buffer's authority.
pub fn write_ix(buffer: &Address, authority: &Address, offset: u32, bytes: &[u8]) -> Instruction {
    let mut data = Vec::with_capacity(16 + bytes.len());
    data.extend_from_slice(&IX_WRITE.to_le_bytes());
    data.extend_from_slice(&offset.to_le_bytes());
    data.extend_from_slice(&(bytes.len() as u64).to_le_bytes());
    data.extend_from_slice(bytes);
    Instruction {
        program_id: BPF_UPGRADEABLE_LOADER_ID,
        accounts: vec![AccountMeta::new(*buffer, false), AccountMeta::new_readonly(*authority, true)],
        data,
    }
}

/// Program bytes one write carries: what is left of a 1232-byte packet after a write of no
/// bytes with its two compute-budget instructions, less one byte for the longer length prefix.
/// This is the CLI's `calculate_max_chunk_size`, so both cut the program at the same offsets:
/// 960 bytes when the payer is also the buffer's authority, whatever the buffer's address.
pub fn chunk_len(payer: &Address) -> Result<usize> {
    let any_buffer = Address::new_from_array([0xb5; 32]);
    let ixs = tx::with_compute_budget(tx::MAX_COMPUTE_UNITS, 0, [write_ix(&any_buffer, payer, 0, &[])]);
    let msg = VersionedMessage::Legacy(Message::new_with_blockhash(&ixs, Some(payer), &Hash::default()));
    let unsigned = tx::make_transaction(msg, None).map_err(|e| anyhow!("{e}"))?;
    let len = tx::serialize(&unsigned).map_err(|e| anyhow!("{e}"))?.len();
    Ok(tx::PACKET_DATA_SIZE.saturating_sub(len).saturating_sub(1))
}

/// Chunks of `program` (by index, `chunk` bytes each) that `held` does not already contain.
pub fn differing_chunks(program: &[u8], held: &[u8], chunk: usize) -> Vec<usize> {
    let chunk = chunk.max(1);
    program
        .chunks(chunk)
        .enumerate()
        .filter(|(i, bytes)| held.get(i * chunk..i * chunk + bytes.len()) != Some(*bytes))
        .map(|(i, _)| i)
        .collect()
}

/// Number of chunks a program of `len` bytes is cut into.
pub fn chunk_count(len: usize, chunk: usize) -> usize {
    len.div_ceil(chunk.max(1))
}

/// The account length whose rent-exempt minimum the pinned CLI puts into a new buffer (Agave
/// 4.1.2 `cli/src/program.rs`: 1425-1429 and 2556-2571 for a deploy, 1714-1723 for
/// `write-buffer`). The amounts differ by mode:
///
/// * fresh: the ProgramData rent for `--max-len`; the final transaction drains the buffer into
///   the payer, who pays the ProgramData with it;
/// * upgrade: the rent of a ProgramData holding just this build (45 + its length);
/// * buffer: the buffer's own rent (37 + the build's length).
pub fn buffer_rent_len(mode: DeployMode, so_len: u64, max_len: u64) -> u64 {
    match mode {
        DeployMode::Fresh => PROGRAMDATA_HEADER_LEN + max_len,
        DeployMode::Upgrade => PROGRAMDATA_HEADER_LEN + so_len,
        DeployMode::Buffer => BUFFER_HEADER_LEN + so_len,
    }
}

/// What sits at a buffer address.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Held {
    /// No account.
    Absent,
    /// An upgradeable-loader Buffer.
    Buffer {
        /// Who may write it, hand it over or close it (`None`: nobody).
        authority: Option<Address>,
        /// Lamports in the account.
        lamports: u64,
        /// The program bytes behind the 37-byte header.
        bytes: Vec<u8>,
    },
    /// Anything else, in words.
    Other(String),
}

/// Decode `(owner, data, lamports)` of the account at a buffer address.
pub fn decode_held(account: Option<(Address, Vec<u8>, u64)>) -> Held {
    let Some((owner, data, lamports)) = account else {
        return Held::Absent;
    };
    if owner != BPF_UPGRADEABLE_LOADER_ID {
        return Held::Other(format!("an account owned by {owner}"));
    }
    let tag = data.get(0..4).and_then(|s| s.try_into().ok()).map(u32::from_le_bytes);
    if tag != Some(STATE_BUFFER) || data.len() < BUFFER_HEADER_LEN as usize {
        return Held::Other(
            match tag {
                Some(0) => "an uninitialized loader account",
                Some(2) => "a Program account",
                Some(3) => "a ProgramData account",
                _ => "a loader account that is not a Buffer",
            }
            .to_string(),
        );
    }
    let authority = match data.get(4) {
        Some(1) => data.get(5..37).and_then(|s| <[u8; 32]>::try_from(s).ok()).map(Address::new_from_array),
        _ => None,
    };
    Held::Buffer { authority, lamports, bytes: data.get(BUFFER_HEADER_LEN as usize..).unwrap_or_default().to_vec() }
}

/// A buffer address judged for one deploy.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Resume {
    /// Nothing is there: the deploy creates the buffer.
    Create {
        /// Chunks to write (the ones that are not all zero: a new buffer is zero-filled).
        to_write: Vec<usize>,
    },
    /// This deploy's buffer exists: the deployer's, and sized for this build.
    Continue {
        /// Lamports it already holds.
        lamports: u64,
        /// Chunks that still differ from the build.
        to_write: Vec<usize>,
    },
    /// Something the deploy cannot continue with, in words.
    Refused(String),
}

impl Resume {
    /// Chunks still to write (`None` when refused).
    pub fn to_write(&self) -> Option<&[usize]> {
        match self {
            Resume::Create { to_write } | Resume::Continue { to_write, .. } => Some(to_write),
            Resume::Refused(_) => None,
        }
    }
}

/// Judge what `buffer` holds against the build and the deployer. Only a Buffer whose authority
/// is the deployer and whose size is exactly this build's is continued: the CLI compares the
/// build's bytes only, so a longer buffer would deploy whatever follows them.
pub fn judge(held: &Held, buffer: &Address, deployer: &Address, program: &[u8], chunk: usize) -> Resume {
    match held {
        Held::Absent => Resume::Create { to_write: differing_chunks(program, &vec![0; program.len()], chunk) },
        Held::Other(what) => Resume::Refused(format!("{buffer} holds {what}, not a buffer of this deploy")),
        Held::Buffer { authority: None, .. } => Resume::Refused(format!("{buffer} is a buffer with no authority: nobody can write or close it")),
        Held::Buffer { authority: Some(a), .. } if a != deployer => Resume::Refused(format!(
            "{buffer} is a buffer whose authority is {a}, not the deployer {deployer}: it was handed over, or it is someone else's. \
             Another buffer needs another keypair: move this one's keypair file out of the key directory first (deploy.sh then makes a new one)"
        )),
        Held::Buffer { bytes, .. } if bytes.len() != program.len() => Resume::Refused(format!(
            "{buffer} is the deployer's buffer, but it holds {} program bytes and this build has {}: it was written for another build. \
             Its lamports come back with `solana program close {buffer} --recipient {deployer}`; then deploy again",
            bytes.len(),
            program.len()
        )),
        Held::Buffer { lamports, bytes, .. } => Resume::Continue { lamports: *lamports, to_write: differing_chunks(program, bytes, chunk) },
    }
}

// ---- the paced writer -------------------------------------------------------------------------

/// How fast to send, and how patient to be.
#[derive(Clone, Copy, Debug)]
pub struct Pace {
    /// Pause after a write at full speed (`1 / --rate`).
    pub gap: Duration,
    /// Longest pause after repeated "slow down" answers.
    pub max_gap: Duration,
    /// How often the writes in flight are looked up.
    pub poll: Duration,
    /// A blockhash older than this is replaced before the next signature.
    pub blockhash_age: Duration,
    /// Stop when no write was seen to land for this long.
    pub stall: Duration,
    /// Reads, the simulation and the buffer's creation.
    pub patience: Patience,
}

impl Pace {
    /// `rate` writes a second.
    pub fn per_second(rate: f64) -> Result<Self> {
        if !(rate.is_finite() && rate > 0.0 && rate <= 1_000.0) {
            bail!("--rate must be above 0 and at most 1000 transactions a second (got {rate})");
        }
        let gap = Duration::from_secs_f64(1.0 / rate);
        Ok(Pace {
            gap,
            max_gap: gap.max(Duration::from_secs(30)),
            poll: (gap * 2).clamp(Duration::from_millis(400), Duration::from_secs(2)),
            blockhash_age: Duration::from_secs(10),
            // Five minutes, or four writes' time at a rate slower than that.
            stall: Duration::from_secs(300).max(gap * 4),
            patience: Patience::default(),
        })
    }

    /// The pause after a "slow down" answer: twice the last one, up to `max_gap`.
    fn slower(&self, gap: Duration) -> Duration {
        (gap * 2).min(self.max_gap)
    }

    /// The pause after an accepted write: a tenth shorter, down to full speed.
    fn faster(&self, gap: Duration) -> Duration {
        (gap * 9 / 10).max(self.gap)
    }
}

/// One buffer to fill.
pub struct Job<'a> {
    /// The cluster.
    pub chain: &'a Chain,
    /// Fee payer and buffer authority (one key: the deployer).
    pub payer: &'a Keypair,
    /// The buffer's address.
    pub buffer: Address,
    /// The program file.
    pub program: &'a [u8],
    /// Program bytes per write ([`chunk_len`]).
    pub chunk: usize,
    /// Priority price, micro-lamports per compute unit.
    pub cu_price: u64,
    /// The pace.
    pub pace: Pace,
}

/// What the writer did.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Tally {
    /// Writes an RPC took, or may have taken (a send that got no answer).
    pub sent: u64,
    /// Writes seen confirmed.
    pub landed: u64,
    /// Writes signed again because their blockhash expired first.
    pub resigned: u64,
    /// "Slow down" answers and timeouts.
    pub slowed: u64,
    /// Highest slot a write landed in.
    pub last_slot: u64,
    /// Compute-unit limit of each write (from one simulation).
    pub cu_limit: u32,
}

struct Flight {
    chunk: usize,
    signature: String,
    last_valid: u64,
}

struct Board {
    queue: VecDeque<usize>,
    flying: Vec<Flight>,
    tally: Tally,
    failed: Option<String>,
    progress: Instant,
    last_answer: String,
    total: usize,
    said: Instant,
}

fn lock(board: &Mutex<Board>) -> MutexGuard<'_, Board> {
    board.lock().unwrap_or_else(PoisonError::into_inner)
}

impl Job<'_> {
    fn write_ixs(&self, chunk: usize, cu_limit: u32) -> Vec<Instruction> {
        let start = chunk * self.chunk;
        let end = (start + self.chunk).min(self.program.len());
        let bytes = self.program.get(start..end).unwrap_or_default();
        tx::with_compute_budget(cu_limit, self.cu_price, [write_ix(&self.buffer, &self.payer.pubkey(), start as u32, bytes)])
    }

    async fn held(&self) -> Result<Held> {
        read_held(self.chain, &self.buffer, self.pace.patience).await
    }

    /// What the buffer holds, judged for this build. A buffer that was just created may not
    /// show yet on every node behind a load-balanced RPC: "absent" is asked again a few times.
    async fn plan(&self) -> Result<Resume> {
        let mut plan = judge(&self.held().await?, &self.buffer, &self.payer.pubkey(), self.program, self.chunk);
        for _ in 0..NOT_YET_VISIBLE_TRIES {
            if !matches!(plan, Resume::Create { .. }) {
                break;
            }
            tokio::time::sleep(self.pace.poll).await;
            plan = judge(&self.held().await?, &self.buffer, &self.payer.pubkey(), self.program, self.chunk);
        }
        Ok(plan)
    }

    /// Compute units one write uses, from a simulation of `chunk` (every write costs the same:
    /// the loader and the compute-budget program charge fixed amounts).
    async fn simulate(&self, chunk: usize) -> Result<u32> {
        let p = self.pace.patience;
        let mut tries = 0;
        loop {
            let (hash, _) = patiently(p, "getLatestBlockhash", || self.chain.rpc.get_latest_blockhash()).await?;
            let (_, wire) = sign_tx(&[self.payer], &self.write_ixs(chunk, tx::MAX_COMPUTE_UNITS), &hash)?;
            let sim = patiently(p, "simulateTransaction", || self.chain.rpc.simulate_transaction(&wire)).await?;
            match sim.err {
                None => {
                    let used = sim.units_consumed.ok_or_else(|| anyhow!("simulateTransaction reported no compute units"))?;
                    return u32::try_from(used).map_err(|_| anyhow!("simulateTransaction reported {used} compute units"));
                }
                // The node that simulated may not have seen the buffer's creation yet.
                Some(_) if tries < NOT_YET_VISIBLE_TRIES => {
                    tries += 1;
                    tokio::time::sleep(self.pace.poll).await;
                }
                Some(err) => bail!("a write to {} does not simulate: {err}\n  {}", self.buffer, tail(&sim.logs, 25).join("\n  ")),
            }
        }
    }

    /// Send the writes of `board.queue`, one every `pace.gap`, slower after "slow down".
    async fn send_loop(&self, board: &Mutex<Board>, cu_limit: u32) -> Result<()> {
        let mut gap = self.pace.gap;
        let mut blockhash: Option<(Hash, u64, Instant)> = None;
        let mut used_up = 0u32;
        loop {
            let next = {
                let b = lock(board);
                if let Some(why) = &b.failed {
                    bail!("{why}");
                }
                if b.queue.is_empty() && b.flying.is_empty() {
                    return Ok(());
                }
                // Only a write seen to land counts: an RPC takes a write the payer cannot pay
                // for, or one the cluster drops, and it never lands however often it is signed.
                if b.progress.elapsed() > self.pace.stall {
                    bail!(
                        "no write was seen to land for {} s ({} sent and not confirmed, {} not sent yet); the RPC's last answer: {}",
                        self.pace.stall.as_secs(),
                        b.flying.len(),
                        b.queue.len(),
                        if b.last_answer.is_empty() { "none" } else { &b.last_answer }
                    );
                }
                if b.flying.len() >= MAX_IN_FLIGHT {
                    None
                } else {
                    b.queue.front().copied()
                }
            };
            let Some(chunk) = next else {
                tokio::time::sleep(self.pace.poll).await;
                continue;
            };
            let fresh = blockhash.as_ref().filter(|(_, _, at)| at.elapsed() <= self.pace.blockhash_age);
            let (hash, last_valid) = match fresh {
                Some((hash, last_valid, _)) => (hash.clone(), *last_valid),
                None => match self.chain.rpc.get_latest_blockhash().await {
                    Ok((hash, last_valid)) => {
                        blockhash = Some((hash.clone(), last_valid, Instant::now()));
                        (hash, last_valid)
                    }
                    Err(e) => {
                        gap = self.slow_down(board, gap, &e, &mut used_up)?;
                        tokio::time::sleep(gap).await;
                        continue;
                    }
                },
            };
            let (signature, wire) = sign_tx(&[self.payer], &self.write_ixs(chunk, cu_limit), &hash)?;
            // A write is in flight once the RPC took it, and also when no answer came (a
            // timeout, a dropped connection, an error page): a node may have taken it all the
            // same. It is then watched like the others and signed again only once its blockhash
            // has expired; sending the chunk again at once could land it twice. Only a refusal
            // ("too many requests") leaves the chunk at the head of the queue.
            let in_flight = match self.chain.submit(&wire).await {
                Ok(_) => {
                    used_up = 0;
                    gap = self.pace.faster(gap);
                    true
                }
                Err(e) if retry_later(&e) => {
                    gap = self.slow_down(board, gap, &e, &mut used_up)?;
                    !refused(&e)
                }
                Err(e) => bail!("the RPC refused the write at offset {}: {e}", chunk * self.chunk),
            };
            if in_flight {
                let mut b = lock(board);
                b.queue.pop_front();
                b.flying.push(Flight { chunk, signature, last_valid });
                b.tally.sent += 1;
            }
            tokio::time::sleep(gap).await;
        }
    }

    /// Refuse to start on writes the payer cannot pay for. A write whose fee the payer lacks
    /// is taken by an RPC and never lands, so nothing but the stall time would end the run.
    async fn can_pay(&self, writes: usize, cu_limit: u32) -> Result<()> {
        let p = self.pace.patience;
        let payer = self.payer.pubkey();
        let fee = tx::fee_for(0, cu_limit, self.cu_price, tx::LAMPORTS_PER_SIGNATURE);
        let fees = fee.saturating_mul(writes as u64);
        let balance = patiently(p, "getBalance", || self.chain.rpc.get_balance(&payer)).await?;
        // A fee payer may not be left below the rent-exempt minimum of an account.
        let keep = patiently(p, "getMinimumBalanceForRentExemption", || self.chain.rpc.get_minimum_balance_for_rent_exemption(0)).await?;
        let need = fees.saturating_add(keep);
        if balance < need {
            bail!(
                "the payer {payer} holds {} SOL, and the {writes} write(s) still to send need {} SOL: {} in fees ({fee} lamports each) \
                 and the {} a fee payer has to keep (the rent-exempt minimum). Send it at least {} SOL, then run this again: it continues",
                sol(balance),
                sol(need),
                sol(fees),
                sol(keep),
                sol(need - balance)
            );
        }
        Ok(())
    }

    /// A "slow down" answer: count it and double the pause.
    fn slow_down(&self, board: &Mutex<Board>, gap: Duration, e: &RpcError, used_up: &mut u32) -> Result<Duration> {
        let mut b = lock(board);
        b.tally.slowed += 1;
        b.last_answer = e.to_string();
        if credits_used_up(e) {
            *used_up += 1;
            if *used_up >= 3 {
                bail!("the RPC key's credits are used up ({e}): waiting does not bring them back. Run it again over another RPC (scripts/mainnet/deploy.sh --public-rpc); it resumes");
            }
        }
        Ok(self.pace.slower(gap))
    }

    /// Look the writes in flight up until none is left: confirmed ones are done, expired ones
    /// go back into the queue to be signed again.
    async fn confirm_loop(&self, board: &Mutex<Board>) -> Result<()> {
        loop {
            tokio::time::sleep(self.pace.poll).await;
            let flights: Vec<(usize, String, u64)> = {
                let b = lock(board);
                if b.failed.is_some() || (b.queue.is_empty() && b.flying.is_empty()) {
                    return Ok(());
                }
                b.flying.iter().map(|f| (f.chunk, f.signature.clone(), f.last_valid)).collect()
            };
            if flights.is_empty() {
                continue;
            }
            // The height is read before the statuses: a write that is still unknown once the
            // height has passed its last valid block can no longer land.
            let height = self.chain.rpc.get_block_height().await.ok();
            for batch in flights.chunks(MAX_IN_FLIGHT) {
                let sigs: Vec<String> = batch.iter().map(|(_, s, _)| s.clone()).collect();
                let Ok(statuses) = self.chain.rpc.get_signature_statuses(&sigs).await else {
                    continue;
                };
                let mut b = lock(board);
                for ((chunk, signature, last_valid), status) in batch.iter().zip(statuses) {
                    match status {
                        Some(s) if s.is_confirmed() => {
                            b.flying.retain(|f| f.signature != *signature);
                            match s.err {
                                Some(err) => {
                                    b.failed = Some(format!("the write at offset {} failed on-chain (tx {signature}): {err}", chunk * self.chunk));
                                }
                                None => {
                                    b.tally.landed += 1;
                                    b.tally.last_slot = b.tally.last_slot.max(s.slot);
                                    b.progress = Instant::now();
                                }
                            }
                        }
                        // Processed, not confirmed yet: wait for it.
                        Some(_) => {}
                        None if height.is_some_and(|h| h > *last_valid) => {
                            b.flying.retain(|f| f.signature != *signature);
                            b.queue.push_back(*chunk);
                            b.tally.resigned += 1;
                        }
                        None => {}
                    }
                }
                let done = b.total.saturating_sub(b.queue.len() + b.flying.len());
                if b.said.elapsed() >= Duration::from_secs(15) || (done == b.total && b.failed.is_none()) {
                    b.said = Instant::now();
                    println!(
                        "write-buffer: {done} of {} writes confirmed, {} in flight ({} signed again, {} slow-downs)",
                        b.total,
                        b.flying.len(),
                        b.tally.resigned,
                        b.tally.slowed
                    );
                }
            }
        }
    }

    /// Write the chunks `todo` and return once every one of them is confirmed.
    async fn write(&self, todo: &[usize], tally: &mut Tally) -> Result<()> {
        let now = Instant::now();
        let board = Mutex::new(Board {
            queue: todo.iter().copied().collect(),
            flying: Vec::new(),
            tally: *tally,
            failed: None,
            progress: now,
            last_answer: String::new(),
            total: todo.len(),
            said: now,
        });
        let outcome = tokio::try_join!(self.send_loop(&board, tally.cu_limit), self.confirm_loop(&board));
        *tally = lock(&board).tally;
        outcome.map(|_| ())
    }

    /// Bring the buffer to hold exactly the program: read it, write the chunks that differ, and
    /// read it again, until a read shows every byte equal to the file. That last read is the
    /// check the command ends with. Returns the lamports the buffer holds.
    pub async fn fill(&self, tally: &mut Tally) -> Result<u64> {
        for pass in 0..=PASSES {
            let (lamports, to_write) = match self.plan().await? {
                Resume::Continue { lamports, to_write } => (lamports, to_write),
                Resume::Create { .. } => bail!("the buffer {} does not exist: it was closed, or its creation has not landed", self.buffer),
                Resume::Refused(why) => bail!("{why}"),
            };
            let Some(first) = to_write.first() else {
                return Ok(lamports);
            };
            if pass == PASSES {
                bail!("{} chunks of {} still differ after {PASSES} passes", to_write.len(), self.buffer);
            }
            if pass > 0 {
                println!("write-buffer: {} chunks differ in the read-back: writing them again", to_write.len());
            }
            if tally.cu_limit == 0 {
                tally.cu_limit = self.simulate(*first).await?;
            }
            self.can_pay(to_write.len(), tally.cu_limit).await?;
            self.write(&to_write, tally).await?;
            // Every write is confirmed; give a load-balanced RPC one poll interval to show the
            // last block before the buffer is read back.
            tokio::time::sleep(self.pace.poll).await;
        }
        bail!("the buffer {} was not filled", self.buffer)
    }
}

/// Read and decode what `buffer` holds.
pub async fn read_held(chain: &Chain, buffer: &Address, p: Patience) -> Result<Held> {
    let account = patiently(p, "getAccountInfo", || chain.rpc.get_account(buffer)).await?;
    Ok(decode_held(account.map(|a| (a.owner, a.data, a.lamports))))
}

// ---- write-buffer -----------------------------------------------------------------------------

/// Options for [`write_buffer`].
pub struct WriteBufferOpts {
    /// Target cluster.
    pub cluster: Cluster,
    /// JSON-RPC URL.
    pub rpc: String,
    /// The built program.
    pub so: PathBuf,
    /// The buffer's keypair (it signs the creation only).
    pub buffer: PathBuf,
    /// Fee payer and buffer authority: the deployer's keypair.
    pub authority: PathBuf,
    /// What the buffer is for; it decides the lamports a new buffer gets.
    pub mode: DeployMode,
    /// `--max-len` of a fresh deploy.
    pub max_len: u64,
    /// Writes a second.
    pub rate: f64,
    /// Priority fee, micro-lamports per CU.
    pub cu_price: u64,
    /// Mainnet: actually send.
    pub yes: bool,
    /// Write what was done here (public values only).
    pub json: Option<PathBuf>,
}

fn mode_name(mode: DeployMode) -> String {
    format!("{mode:?}").to_lowercase()
}

/// Create the buffer if it is missing, write what differs from the build at `--rate`, and read
/// it back. Safe to kill and to run again.
pub async fn write_buffer(o: WriteBufferOpts) -> Result<()> {
    let pace = Pace::per_second(o.rate)?;
    let (chain, _) = cluster::connect(o.cluster, &o.rpc).await?;
    let program = std::fs::read(&o.so).with_context(|| format!("read {}", o.so.display()))?;
    let so = so_info(&program)?;
    if o.mode == DeployMode::Fresh && o.max_len < so.len {
        bail!("--max-len {} is smaller than the program ({} bytes)", o.max_len, so.len);
    }
    let payer = read_keypair(&o.authority)?;
    let deployer = payer.pubkey();
    let buffer_key = read_keypair(&o.buffer)?;
    let buffer = buffer_key.pubkey();
    if buffer == deployer {
        bail!("the buffer keypair and the deployer keypair are the same key ({buffer})");
    }
    let chunk = chunk_len(&deployer)?;
    let total = chunk_count(program.len(), chunk);
    let job = Job { chain: &chain, payer: &payer, buffer, program: &program, chunk, cu_price: o.cu_price, pace };
    let p = pace.patience;

    let rent_len = buffer_rent_len(o.mode, so.len, o.max_len);
    let plan = judge(&job.held().await?, &buffer, &deployer, &program, chunk);
    let to_write = match &plan {
        Resume::Refused(why) => bail!("{why}"),
        Resume::Create { to_write } | Resume::Continue { to_write, .. } => to_write.len(),
    };
    let already = total - to_write;
    let balance_before = patiently(p, "getBalance", || chain.rpc.get_balance(&deployer)).await?;
    println!("write-buffer plan ({}, mode {}) via {}:", o.cluster.name(), mode_name(o.mode), redact_url(&o.rpc));
    println!("  program   {} ({} bytes, sha256 {})", o.so.display(), so.len, so.sha256);
    println!("  payer     {deployer} (also the buffer's authority)");
    let created_with = match &plan {
        Resume::Continue { lamports, .. } => {
            println!("  buffer    {buffer} exists: the payer's, {} SOL in it", sol(*lamports));
            None
        }
        _ => {
            let lamports = patiently(p, "getMinimumBalanceForRentExemption", || {
                chain.rpc.get_minimum_balance_for_rent_exemption(usize::try_from(rent_len).unwrap_or(usize::MAX))
            })
            .await?;
            println!(
                "  buffer    {buffer} does not exist: created with {} SOL (the rent of {rent_len} bytes, what the Solana CLI puts in for mode {})",
                sol(lamports),
                mode_name(o.mode)
            );
            Some(lamports)
        }
    };
    println!(
        "  writes    {to_write} of {total} chunks of {chunk} bytes still to write, at most {} a second: about {} s",
        o.rate,
        (to_write as f64 / o.rate).ceil()
    );
    println!("  each      one transaction on a recent blockhash, compute-unit limit from one simulation, {} micro-lamports per unit", o.cu_price);
    require_yes(o.cluster, o.yes, "the buffer write")?;

    let started = Instant::now();
    if let Some(lamports) = created_with {
        let ixs = create_buffer_ixs(&deployer, &buffer, &deployer, lamports, so.len);
        match chain.send_patiently(&[&payer, &buffer_key], &ixs, o.cu_price, p).await {
            Ok(l) => println!("write-buffer: created {buffer} with {lamports} lamports (tx {})", l.signature),
            // An earlier run's creation may have landed after that run was stopped.
            Err(e) => match judge(&job.held().await?, &buffer, &deployer, &program, chunk) {
                Resume::Continue { .. } => println!("write-buffer: {buffer} exists already (an earlier run created it)"),
                _ => return Err(e.context(format!("create the buffer {buffer}"))),
            },
        }
    }
    let mut tally = Tally::default();
    // `fill` returns only after a read of the buffer showed every byte equal to the file.
    let outcome = job.fill(&mut tally).await;
    let (buffer_lamports, left) = match &outcome {
        Ok(lamports) => (Some(*lamports), Some(0)),
        Err(_) => match job.held().await.ok().map(|h| judge(&h, &buffer, &deployer, &program, chunk)) {
            Some(Resume::Continue { lamports, to_write }) => (Some(lamports), Some(to_write.len())),
            _ => (None, None),
        },
    };
    let balance_after = patiently(p, "getBalance", || chain.rpc.get_balance(&deployer)).await.ok();
    // What the payer spent on fees: its balance change, less what went into a buffer it created.
    let fees = balance_after.map(|after| balance_before.saturating_sub(after).saturating_sub(created_with.unwrap_or(0)));
    let now = unix_now();
    let summary = json!({
        "schema": "heads-down/buffer-write/v1",
        "cluster": o.cluster.name(),
        "mode": mode_name(o.mode),
        "buffer": buffer.to_string(),
        "authority": deployer.to_string(),
        "so": { "len": so.len, "sha256": so.sha256 },
        "created": created_with.is_some(),
        "buffer_lamports": buffer_lamports,
        "chunk_bytes": chunk,
        "chunks_total": total,
        "chunks_already_written": already,
        "chunks_left": left,
        "transactions_sent": tally.sent,
        "transactions_landed": tally.landed,
        "signed_again": tally.resigned,
        "slow_downs": tally.slowed,
        "compute_unit_limit": tally.cu_limit,
        "cu_price_micro_lamports": o.cu_price,
        "rate_per_second": o.rate,
        "last_slot": tally.last_slot,
        "fees_lamports": fees,
        "seconds": started.elapsed().as_secs(),
        "verified": outcome.is_ok(),
        "recorded_at": rfc3339(now),
    });
    if let Some(path) = &o.json {
        write_json(path, &summary)?;
    }
    let counts = format!(
        "{} writes sent, {} confirmed, {} signed again, {} slow-downs, {} lamports of fees, {} s",
        tally.sent,
        tally.landed,
        tally.resigned,
        tally.slowed,
        fees.map_or_else(|| "?".to_string(), |f| f.to_string()),
        started.elapsed().as_secs()
    );
    match outcome {
        Ok(_) => {
            println!("write-buffer: {buffer} holds exactly {} ({} bytes read back and compared); {counts}", o.so.display(), so.len);
            Ok(())
        }
        Err(e) => {
            println!(
                "write-buffer: STOPPED with {} of {total} chunks written ({counts}); the payer holds {} SOL. Nothing is lost: run it again and it continues",
                left.map_or_else(|| "?".to_string(), |l| (total - l).to_string()),
                balance_after.map_or_else(|| "?".to_string(), sol)
            );
            Err(e)
        }
    }
}

// ---- buffer-status ----------------------------------------------------------------------------

/// Options for [`buffer_status`].
pub struct BufferStatusOpts {
    /// Target cluster.
    pub cluster: Cluster,
    /// JSON-RPC URL.
    pub rpc: String,
    /// The built program.
    pub so: PathBuf,
    /// The buffer's address.
    pub buffer: Address,
    /// The deployer (fee payer and buffer authority).
    pub deployer: Address,
    /// Write the answer here (JSON).
    pub json: Option<PathBuf>,
}

/// What a deploy would find at `buffer`, as JSON: `state` is `absent`, `resumable` or `refused`.
pub fn resume_json(buffer: &Address, held: &Held, plan: &Resume, total: usize, chunk: usize) -> Value {
    let (state, reason) = match plan {
        Resume::Create { .. } => ("absent", None),
        Resume::Continue { .. } => ("resumable", None),
        Resume::Refused(why) => ("refused", Some(why.clone())),
    };
    let (authority, lamports, program_bytes) = match held {
        Held::Buffer { authority, lamports, bytes } => (authority.map(|a| a.to_string()), Some(*lamports), Some(bytes.len())),
        _ => (None, None, None),
    };
    json!({
        "address": buffer.to_string(),
        "state": state,
        "reason": reason,
        "authority": authority,
        "lamports": lamports,
        "program_bytes": program_bytes,
        "chunk_bytes": chunk,
        "chunks_total": total,
        "chunks_to_write": plan.to_write().map(<[usize]>::len),
    })
}

/// One line for what a deploy would find at `buffer`.
pub fn resume_line(buffer: &Address, plan: &Resume, total: usize) -> String {
    match plan {
        Resume::Create { to_write } => format!("{buffer} does not exist yet: {} of {total} chunks to write", to_write.len()),
        Resume::Continue { lamports, to_write } => format!(
            "{buffer} is the deployer's buffer for this build: {} SOL in it, {} of {total} chunks still to write",
            sol(*lamports),
            to_write.len()
        ),
        Resume::Refused(why) => why.clone(),
    }
}

/// Read-only: what the per-commit buffer holds and how many chunks of the build are still to
/// write.
pub async fn buffer_status(o: BufferStatusOpts) -> Result<()> {
    let (chain, _) = cluster::connect(o.cluster, &o.rpc).await?;
    let program = std::fs::read(&o.so).with_context(|| format!("read {}", o.so.display()))?;
    so_info(&program)?;
    let chunk = chunk_len(&o.deployer)?;
    let total = chunk_count(program.len(), chunk);
    let held = read_held(&chain, &o.buffer, Patience::default()).await?;
    let plan = judge(&held, &o.buffer, &o.deployer, &program, chunk);
    println!("buffer: {}", resume_line(&o.buffer, &plan, total));
    if let Some(path) = &o.json {
        let mut v = resume_json(&o.buffer, &held, &plan, total, chunk);
        v["schema"] = json!("heads-down/buffer-status/v1");
        v["cluster"] = json!(o.cluster.name());
        write_json(path, &v)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::sync::Arc;

    use base64::Engine;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    use super::*;

    fn key(n: u8) -> Address {
        Address::new_from_array([n; 32])
    }

    fn ms(n: u64) -> Duration {
        Duration::from_millis(n)
    }

    /// `len` bytes, none of them zero.
    fn program(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i % 251) as u8 + 1).collect()
    }

    fn buffer_account(authority: Option<Address>, bytes: &[u8]) -> Option<(Address, Vec<u8>, u64)> {
        let mut data = STATE_BUFFER.to_le_bytes().to_vec();
        match authority {
            Some(a) => {
                data.push(1);
                data.extend_from_slice(a.as_ref());
            }
            None => data.extend_from_slice(&[0; 33]),
        }
        data.extend_from_slice(bytes);
        Some((BPF_UPGRADEABLE_LOADER_ID, data, 5))
    }

    // ---- what the loader expects --------------------------------------------------------------

    #[test]
    fn a_write_fills_the_packet_where_the_cli_cuts_the_program() {
        let payer = key(1);
        let chunk = chunk_len(&payer).unwrap();
        assert_eq!(chunk, 960);
        let size = |n: usize| {
            let ixs = tx::with_compute_budget(2_670, 100_000, [write_ix(&key(2), &payer, 189_120, &vec![7; n])]);
            let msg = VersionedMessage::Legacy(Message::new_with_blockhash(&ixs, Some(&payer), &Hash::default()));
            tx::serialize(&tx::make_transaction(msg, None).unwrap()).unwrap().len()
        };
        // A full chunk is exactly one packet; one byte more does not fit.
        assert_eq!(size(chunk), tx::PACKET_DATA_SIZE);
        assert_eq!(size(chunk + 1), tx::PACKET_DATA_SIZE + 1);
        // Today's 190,048-byte build is 198 chunks.
        assert_eq!(chunk_count(190_048, chunk), 198);
        assert_eq!(chunk_count(960, chunk), 1);
        assert_eq!(chunk_count(961, chunk), 2);
        assert_eq!(chunk_count(0, chunk), 0);
    }

    #[test]
    fn instructions_have_the_loaders_layout() {
        let (payer, buffer) = (key(1), key(2));
        let w = write_ix(&buffer, &payer, 0x0102_0304, &[9, 8, 7]);
        assert_eq!(w.program_id, BPF_UPGRADEABLE_LOADER_ID);
        assert_eq!(w.data, [&[1u8, 0, 0, 0][..], &[4, 3, 2, 1], &[3, 0, 0, 0, 0, 0, 0, 0], &[9, 8, 7]].concat());
        assert_eq!(w.accounts, vec![AccountMeta::new(buffer, false), AccountMeta::new_readonly(payer, true)]);

        let c = create_buffer_ixs(&payer, &buffer, &payer, 999_647_480, 190_048);
        assert_eq!(c.len(), 2);
        assert_eq!(c[0].program_id, SYSTEM_PROGRAM_ID);
        let mut create = vec![0u8, 0, 0, 0];
        create.extend_from_slice(&999_647_480u64.to_le_bytes());
        create.extend_from_slice(&190_085u64.to_le_bytes());
        create.extend_from_slice(BPF_UPGRADEABLE_LOADER_ID.as_ref());
        assert_eq!(c[0].data, create);
        assert_eq!(c[0].accounts, vec![AccountMeta::new(payer, true), AccountMeta::new(buffer, true)]);
        assert_eq!(c[1].program_id, BPF_UPGRADEABLE_LOADER_ID);
        assert_eq!(c[1].data, vec![0u8, 0, 0, 0]);
        assert_eq!(c[1].accounts, vec![AccountMeta::new(buffer, false), AccountMeta::new_readonly(payer, false)]);
    }

    #[test]
    fn a_new_buffer_is_funded_by_mode_as_the_cli_does() {
        // Today's 190,048-byte build with --max-len 196,608.
        let lens = [DeployMode::Fresh, DeployMode::Upgrade, DeployMode::Buffer].map(|m| buffer_rent_len(m, 190_048, 196_608));
        assert_eq!(lens, [196_653, 190_093, 190_085]);
        // At 5,080 lamports a byte (mainnet's rent on 2026-10-04) that is the 0.9996 SOL of
        // ProgramData rent for a first deploy, and two different amounts for the later modes.
        assert_eq!(lens.map(|l| (l + 128) * 5_080), [999_647_480, 966_322_680, 966_282_040]);
    }

    #[test]
    fn only_chunks_that_differ_are_written() {
        let mut build = program(250);
        build[100..200].fill(0);
        // A new buffer is all zero: the zero chunk is already there.
        assert_eq!(differing_chunks(&build, &[0; 250], 100), vec![0, 2]);
        assert_eq!(differing_chunks(&build, &build, 100), Vec::<usize>::new());
        let mut held = build.clone();
        held[249] ^= 1;
        assert_eq!(differing_chunks(&build, &held, 100), vec![2]);
        // A buffer shorter than the build differs from the first missing byte on.
        assert_eq!(differing_chunks(&build, &build[..150], 100), vec![1, 2]);
    }

    #[test]
    fn what_a_buffer_address_holds_is_judged_for_the_deploy() {
        let (buffer, deployer, other) = (key(2), key(1), key(7));
        let mut build = program(250);
        build[100..200].fill(0);
        let judged = |account| judge(&decode_held(account), &buffer, &deployer, &build, 100);

        assert_eq!(judged(None), Resume::Create { to_write: vec![0, 2] });
        let mut part = vec![0u8; 250];
        part[..100].copy_from_slice(&build[..100]);
        assert_eq!(judged(buffer_account(Some(deployer), &part)), Resume::Continue { lamports: 5, to_write: vec![2] });
        assert_eq!(judged(buffer_account(Some(deployer), &build)), Resume::Continue { lamports: 5, to_write: vec![] });

        let refused = |account| match judged(account) {
            Resume::Refused(why) => why,
            other => panic!("not refused: {other:?}"),
        };
        let handed_over = refused(buffer_account(Some(other), &build));
        assert!(handed_over.contains(&format!("authority is {other}, not the deployer {deployer}")));
        assert!(handed_over.contains("move this one's keypair file out of the key directory"), "{handed_over}");
        assert!(refused(buffer_account(None, &build)).contains("no authority"));
        // A buffer of another size is never continued: the CLI would deploy what follows the build.
        assert!(refused(buffer_account(Some(deployer), &program(251))).contains("251 program bytes and this build has 250"));
        assert!(refused(buffer_account(Some(deployer), &build[..249])).contains("another build"));
        assert!(refused(Some((SYSTEM_PROGRAM_ID, vec![], 9))).contains("an account owned by 11111111111111111111111111111111"));
        let mut programdata = vec![3u8, 0, 0, 0];
        programdata.extend_from_slice(&[0; 60]);
        assert!(refused(Some((BPF_UPGRADEABLE_LOADER_ID, programdata, 9))).contains("a ProgramData account"));
        assert!(refused(Some((BPF_UPGRADEABLE_LOADER_ID, vec![1, 0, 0, 0, 1], 9))).contains("not a Buffer"));
    }

    #[test]
    fn the_rate_sets_the_pace() {
        let p = Pace::per_second(1.0).unwrap();
        assert_eq!((p.gap, p.poll, p.max_gap), (Duration::from_secs(1), Duration::from_secs(2), Duration::from_secs(30)));
        let p = Pace::per_second(50.0).unwrap();
        assert_eq!((p.gap, p.poll), (ms(20), ms(400)));
        assert_eq!(Pace::per_second(0.01).unwrap().max_gap, Duration::from_secs(100));
        // The run gives up after five minutes without a write landing; at a rate slower than
        // one write in 75 s, after four writes' time.
        assert_eq!(Pace::per_second(1.0).unwrap().stall, Duration::from_secs(300));
        assert_eq!(Pace::per_second(50.0).unwrap().stall, Duration::from_secs(300));
        assert_eq!(Pace::per_second(0.01).unwrap().stall, Duration::from_secs(400));
        for bad in [0.0, -1.0, f64::NAN, f64::INFINITY, 1_000.5] {
            assert!(Pace::per_second(bad).is_err(), "{bad}");
        }
        // "Slow down" doubles the pause, up to 30 s; each accepted write takes a tenth off
        // again, down to the rate that was asked for.
        let p = Pace::per_second(1.0).unwrap();
        let s = Duration::from_secs;
        assert_eq!([p.slower(s(1)), p.slower(s(2)), p.slower(s(16)), p.slower(s(30))], [s(2), s(4), s(30), s(30)]);
        assert_eq!([p.faster(s(2)), p.faster(ms(1_800)), p.faster(ms(1_050)), p.faster(s(1))], [ms(1_800), ms(1_620), s(1), s(1)]);
    }

    // ---- a mock JSON-RPC node -----------------------------------------------------------------

    /// How the node says "too many requests".
    #[derive(Clone, Copy)]
    enum Throttle {
        /// Helius: HTTP 429 with a JSON-RPC error, code -32005 (docs/billing/rate-limits).
        Helius,
        /// HTTP 429 with a JSON-RPC error whose code is 429.
        Code429,
        /// HTTP 429 with a body that is not JSON.
        Text,
        /// Helius once the credits are gone: "429 max usage reached" (its FAQ; the exact body
        /// is not documented, a JSON-RPC error is assumed here).
        MaxUsage,
    }

    struct Node {
        buffer: Address,
        /// (owner, lamports, data) of the buffer account.
        account: Option<(Address, u64, Vec<u8>)>,
        height: u64,
        valid_for: u64,
        hashes: u64,
        statuses: HashMap<String, (u64, Option<Value>)>,
        balance: u64,
        // Behaviour.
        min_gap: Option<Duration>,
        throttle: Throttle,
        throttle_all: bool,
        drop_sends: usize,
        hang_sends: usize,
        lose_sends: usize,
        fail_writes: bool,
        /// The next reads of the buffer answer "no such account" (a node that lags behind).
        lagging_reads: usize,
        // What happened.
        last_accepted: Option<Instant>,
        accepted: usize,
        throttled: u64,
        duplicates: usize,
        landed_writes: usize,
        seen_budget: Option<(u32, u64)>,
    }

    fn short_u16(b: &[u8], at: &mut usize) -> usize {
        let (mut v, mut shift) = (0usize, 0);
        loop {
            let byte = b[*at];
            *at += 1;
            v |= usize::from(byte & 0x7f) << shift;
            if byte & 0x80 == 0 {
                return v;
            }
            shift += 7;
        }
    }

    /// Signatures, account keys and `(program index, account indexes, data)` of a legacy
    /// transaction's wire bytes.
    #[allow(clippy::type_complexity)]
    fn parse_legacy(w: &[u8]) -> (Vec<Vec<u8>>, Vec<Address>, Vec<(usize, Vec<u8>, Vec<u8>)>) {
        let mut at = 0;
        let n = short_u16(w, &mut at);
        let sigs: Vec<Vec<u8>> = (0..n).map(|i| w[at + 64 * i..at + 64 * (i + 1)].to_vec()).collect();
        at += 64 * n + 3;
        let n = short_u16(w, &mut at);
        let keys: Vec<Address> = (0..n).map(|i| Address::new_from_array(w[at + 32 * i..at + 32 * (i + 1)].try_into().unwrap())).collect();
        at += 32 * n + 32;
        let n = short_u16(w, &mut at);
        let mut ixs = vec![];
        for _ in 0..n {
            let program = usize::from(w[at]);
            at += 1;
            let accounts = short_u16(w, &mut at);
            let accounts = w[at..at + accounts].to_vec();
            at += accounts.len();
            let data = short_u16(w, &mut at);
            let data = w[at..at + data].to_vec();
            at += data.len();
            ixs.push((program, accounts, data));
        }
        assert_eq!(at, w.len(), "trailing bytes in the transaction");
        (sigs, keys, ixs)
    }

    fn u64_le(b: &[u8]) -> u64 {
        u64::from_le_bytes(b.try_into().unwrap())
    }

    impl Node {
        fn new(buffer: Address) -> Self {
            Node {
                buffer,
                account: None,
                height: 100,
                valid_for: 150,
                hashes: 0,
                statuses: HashMap::new(),
                balance: 5_000_000_000,
                min_gap: None,
                throttle: Throttle::Helius,
                throttle_all: false,
                drop_sends: 0,
                hang_sends: 0,
                lose_sends: 0,
                fail_writes: false,
                lagging_reads: 0,
                last_accepted: None,
                accepted: 0,
                throttled: 0,
                duplicates: 0,
                landed_writes: 0,
                seen_budget: None,
            }
        }

        /// A buffer that exists already: `authority`'s, sized for `len` program bytes, holding
        /// `written` at its start.
        fn create(&mut self, authority: &Address, len: usize, written: &[u8]) {
            let (_, mut data, _) = buffer_account(Some(*authority), &vec![0; len]).unwrap();
            data[37..37 + written.len()].copy_from_slice(written);
            self.account = Some((BPF_UPGRADEABLE_LOADER_ID, 1_000_000, data));
        }

        fn program_bytes(&self) -> &[u8] {
            &self.account.as_ref().unwrap().2[37..]
        }

        /// Execute a transaction as the cluster would, as far as these tests need it.
        fn land(&mut self, wire: &[u8]) -> String {
            let (sigs, keys, ixs) = parse_legacy(wire);
            let sig = bs58::encode(&sigs[0]).into_string();
            if self.statuses.contains_key(&sig) {
                self.duplicates += 1;
                return sig;
            }
            let mut err = None;
            let (mut limit, mut price) = (None, None);
            for (program, accounts, data) in &ixs {
                let program = keys[*program];
                if program == tx::COMPUTE_BUDGET_PROGRAM_ID && data[0] == 2 {
                    limit = Some(u32::from_le_bytes(data[1..5].try_into().unwrap()));
                } else if program == tx::COMPUTE_BUDGET_PROGRAM_ID && data[0] == 3 {
                    price = Some(u64_le(&data[1..9]));
                } else if program == SYSTEM_PROGRAM_ID {
                    assert_eq!(data[0..4], [0, 0, 0, 0], "only CreateAccount is expected");
                    assert_eq!(keys[usize::from(accounts[1])], self.buffer);
                    if self.account.is_some() {
                        err = Some(json!({ "InstructionError": [1, { "Custom": 0 }] }));
                        break;
                    }
                    let owner = Address::new_from_array(data[20..52].try_into().unwrap());
                    self.account = Some((owner, u64_le(&data[4..12]), vec![0; usize::try_from(u64_le(&data[12..20])).unwrap()]));
                    self.balance -= u64_le(&data[4..12]);
                } else if program == BPF_UPGRADEABLE_LOADER_ID && data[0..4] == IX_INITIALIZE_BUFFER.to_le_bytes() {
                    let authority = keys[usize::from(accounts[1])];
                    let acc = &mut self.account.as_mut().unwrap().2;
                    acc[0..4].copy_from_slice(&STATE_BUFFER.to_le_bytes());
                    acc[4] = 1;
                    acc[5..37].copy_from_slice(authority.as_ref());
                } else if program == BPF_UPGRADEABLE_LOADER_ID && data[0..4] == IX_WRITE.to_le_bytes() {
                    assert_eq!(keys[usize::from(accounts[0])], self.buffer);
                    if self.fail_writes {
                        err = Some(json!({ "InstructionError": [2, "IncorrectAuthority"] }));
                        break;
                    }
                    let offset = usize::try_from(u32::from_le_bytes(data[4..8].try_into().unwrap())).unwrap();
                    let len = usize::try_from(u64_le(&data[8..16])).unwrap();
                    assert_eq!(data.len(), 16 + len);
                    self.account.as_mut().expect("a write to a buffer that does not exist").2[37 + offset..37 + offset + len]
                        .copy_from_slice(&data[16..]);
                    self.landed_writes += 1;
                    self.seen_budget = limit.zip(price);
                } else {
                    panic!("the mock node does not know this instruction");
                }
            }
            self.balance -= 5_000 * sigs.len() as u64;
            self.statuses.insert(sig.clone(), (self.height, err));
            sig
        }

        /// The HTTP status line and body for one JSON-RPC request; `None` = no answer at all.
        fn answer(&mut self, req: &Value) -> Option<(&'static str, String)> {
            fn ok(v: Value) -> Option<(&'static str, String)> {
                Some(("200 OK", json!({ "jsonrpc": "2.0", "id": 1, "result": v }).to_string()))
            }
            fn at(v: Value) -> Value {
                json!({ "context": { "slot": 1 }, "value": v })
            }
            let p = &req["params"];
            match req["method"].as_str().unwrap() {
                "getGenesisHash" => ok(json!("MockGenesis111111111111111111111111111111111")),
                "getBalance" => ok(at(json!(self.balance))),
                "getMinimumBalanceForRentExemption" => ok(json!((p[0].as_u64().unwrap() + 128) * 6_960)),
                "getAccountInfo" => {
                    let lagging = self.lagging_reads > 0;
                    self.lagging_reads = self.lagging_reads.saturating_sub(1);
                    let account = self.account.as_ref().filter(|_| !lagging && p[0] == json!(self.buffer.to_string()));
                    ok(at(account.map_or(Value::Null, |(owner, lamports, data)| {
                        let b64 = base64::engine::general_purpose::STANDARD.encode(data);
                        json!({ "owner": owner.to_string(), "lamports": lamports, "data": [b64, "base64"], "executable": false, "space": data.len() })
                    })))
                }
                "getLatestBlockhash" => {
                    self.hashes += 1;
                    let mut hash = [0u8; 32];
                    hash[..8].copy_from_slice(&self.hashes.to_le_bytes());
                    ok(at(json!({ "blockhash": bs58::encode(hash).into_string(), "lastValidBlockHeight": self.height + self.valid_for })))
                }
                // Every look at the height is one block later.
                "getBlockHeight" => {
                    self.height += 1;
                    ok(json!(self.height))
                }
                "simulateTransaction" => ok(at(json!({ "err": null, "logs": [], "unitsConsumed": 2_670 }))),
                "getSignatureStatuses" => {
                    let known = |s: &Value| {
                        self.statuses.get(s.as_str().unwrap()).map(|(slot, err)| json!({ "slot": slot, "confirmations": null, "err": err, "confirmationStatus": "confirmed" }))
                    };
                    ok(at(json!(p[0].as_array().unwrap().iter().map(known).collect::<Vec<_>>())))
                }
                "sendTransaction" => {
                    // One call per transaction: no preflight, and re-sending left to the node.
                    assert_eq!(p[1]["skipPreflight"], json!(true));
                    assert!(p[1].get("maxRetries").is_none());
                    let wire = base64::engine::general_purpose::STANDARD.decode(p[0].as_str().unwrap()).unwrap();
                    if self.hang_sends > 0 {
                        // The node takes the transaction, and the answer never arrives.
                        self.hang_sends -= 1;
                        self.land(&wire);
                        return None;
                    }
                    if self.lose_sends > 0 {
                        // The request is lost on the way: no answer, and no node has it.
                        self.lose_sends -= 1;
                        return None;
                    }
                    let now = Instant::now();
                    let too_soon = self.min_gap.is_some_and(|gap| self.last_accepted.is_some_and(|t| now.duration_since(t) < gap));
                    if self.throttle_all || too_soon {
                        self.throttled += 1;
                        let body = match self.throttle {
                            Throttle::Helius => json!({ "jsonrpc": "2.0", "error": { "code": -32005, "message": "Too many requests" }, "id": "1" }).to_string(),
                            Throttle::Code429 => json!({ "jsonrpc": "2.0", "error": { "code": 429, "message": "Too many requests for a specific RPC call" }, "id": 1 }).to_string(),
                            Throttle::Text => "Too Many Requests".to_string(),
                            Throttle::MaxUsage => json!({ "jsonrpc": "2.0", "error": { "code": -32005, "message": "max usage reached" }, "id": "1" }).to_string(),
                        };
                        return Some(("429 Too Many Requests", body));
                    }
                    self.last_accepted = Some(now);
                    self.accepted += 1;
                    if self.drop_sends > 0 {
                        // Accepted, and never seen again.
                        self.drop_sends -= 1;
                        return ok(json!(bs58::encode(&parse_legacy(&wire).0[0]).into_string()));
                    }
                    ok(json!(self.land(&wire)))
                }
                other => panic!("the mock node has no {other}"),
            }
        }
    }

    async fn serve(listener: TcpListener, node: Arc<Mutex<Node>>) {
        loop {
            let Ok((mut sock, _)) = listener.accept().await else { return };
            let node = node.clone();
            tokio::spawn(async move {
                let mut buf: Vec<u8> = Vec::new();
                loop {
                    // One request: the headers, then `content-length` bytes of JSON.
                    let mut more = [0u8; 16_384];
                    let head = loop {
                        if let Some(i) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                            break i + 4;
                        }
                        match sock.read(&mut more).await {
                            Ok(0) | Err(_) => return,
                            Ok(n) => buf.extend_from_slice(&more[..n]),
                        }
                    };
                    let headers = String::from_utf8_lossy(&buf[..head]).to_ascii_lowercase();
                    let len: usize = headers.lines().find_map(|l| l.strip_prefix("content-length:")).and_then(|v| v.trim().parse().ok()).unwrap_or(0);
                    while buf.len() < head + len {
                        match sock.read(&mut more).await {
                            Ok(0) | Err(_) => return,
                            Ok(n) => buf.extend_from_slice(&more[..n]),
                        }
                    }
                    let req: Value = serde_json::from_slice(&buf[head..head + len]).unwrap();
                    buf.drain(..head + len);
                    let reply = node.lock().unwrap().answer(&req);
                    let Some((status, body)) = reply else {
                        tokio::time::sleep(Duration::from_secs(30)).await;
                        return;
                    };
                    let head = format!("HTTP/1.1 {status}\r\ncontent-type: application/json\r\ncontent-length: {}\r\n\r\n", body.len());
                    if sock.write_all(head.as_bytes()).await.is_err() || sock.write_all(body.as_bytes()).await.is_err() {
                        return;
                    }
                }
            });
        }
    }

    /// Start a node for `buffer`; returns it and its URL.
    async fn node(buffer: Address) -> (Arc<Mutex<Node>>, String) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let node = Arc::new(Mutex::new(Node::new(buffer)));
        tokio::spawn(serve(listener, node.clone()));
        (node, url)
    }

    /// A pace for tests: milliseconds where the real one has seconds.
    fn quick() -> Pace {
        let patience = Patience { first: ms(5), max: ms(40), tries: 5, poll: ms(10) };
        Pace { gap: ms(2), max_gap: ms(80), poll: ms(10), blockhash_age: ms(50), stall: Duration::from_secs(20), patience }
    }

    fn job<'a>(chain: &'a Chain, payer: &'a Keypair, buffer: Address, program: &'a [u8], pace: Pace) -> Job<'a> {
        Job { chain, payer, buffer, program, chunk: chunk_len(&payer.pubkey()).unwrap(), cu_price: 100_000, pace }
    }

    // ---- the paced writer against the mock node -----------------------------------------------

    #[tokio::test]
    async fn writes_only_what_differs_and_ends_on_a_read_that_matches() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(20 * 960 + 100);
        let (node, url) = node(buffer).await;
        // A deploy that stopped part way: the first 8 of 21 chunks are there.
        node.lock().unwrap().create(&payer.pubkey(), build.len(), &build[..8 * 960]);
        let chain = Chain::with_timeout(&url, ms(500)).unwrap();
        let mut tally = Tally::default();
        let lamports = job(&chain, &payer, buffer, &build, quick()).fill(&mut tally).await.unwrap();

        {
            let n = node.lock().unwrap();
            assert_eq!(n.program_bytes(), build);
            assert_eq!(lamports, 1_000_000);
            assert_eq!((tally.sent, tally.landed, n.landed_writes, n.accepted), (13, 13, 13, 13));
            assert_eq!((tally.resigned, tally.slowed), (0, 0));
            // Each write carries the simulated compute-unit limit and the configured price.
            assert_eq!(tally.cu_limit, 2_670);
            assert_eq!(n.seen_budget, Some((2_670, 100_000)));
        }
        // A second run finds nothing to do and sends nothing.
        let mut again = Tally::default();
        job(&chain, &payer, buffer, &build, quick()).fill(&mut again).await.unwrap();
        assert_eq!((again.sent, node.lock().unwrap().accepted), (0, 13));
    }

    #[tokio::test]
    async fn too_many_requests_slows_the_writer_down_and_it_finishes() {
        for shape in [Throttle::Helius, Throttle::Code429, Throttle::Text] {
            let (payer, buffer) = (Keypair::new(), key(9));
            let build = program(8 * 960);
            let (node, url) = node(buffer).await;
            {
                let mut n = node.lock().unwrap();
                n.create(&payer.pubkey(), build.len(), &[]);
                // The node takes one send every 25 ms; the writer starts at one every 2 ms.
                n.min_gap = Some(ms(25));
                n.throttle = shape;
            }
            let chain = Chain::with_timeout(&url, ms(500)).unwrap();
            let mut tally = Tally::default();
            job(&chain, &payer, buffer, &build, quick()).fill(&mut tally).await.unwrap();
            let n = node.lock().unwrap();
            assert_eq!(n.program_bytes(), build);
            assert!(tally.slowed > 0);
            assert_eq!(tally.slowed, n.throttled);
            // Nothing was written twice, and nothing was signed again for it.
            assert_eq!((tally.sent, n.landed_writes, tally.resigned), (8, 8, 0));
        }
    }

    #[tokio::test]
    async fn a_send_that_got_no_answer_is_watched_and_not_sent_a_second_time() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(4 * 960);
        let (node, url) = node(buffer).await;
        {
            let mut n = node.lock().unwrap();
            n.create(&payer.pubkey(), build.len(), &[]);
            // The node takes the first two writes, and its answers never arrive.
            n.hang_sends = 2;
        }
        let chain = Chain::with_timeout(&url, ms(100)).unwrap();
        let mut tally = Tally::default();
        // The blockhash is long replaced when the timeout is over, as it is at the real pace
        // (10 s against a 15 s timeout): the chunk must not be signed and sent again for that.
        job(&chain, &payer, buffer, &build, quick()).fill(&mut tally).await.unwrap();
        let n = node.lock().unwrap();
        assert_eq!(n.program_bytes(), build);
        // Each chunk reached the node once: the two unanswered writes were found landed.
        assert_eq!((n.duplicates, n.landed_writes, n.accepted), (0, 4, 2));
        assert_eq!((tally.slowed, tally.sent, tally.landed, tally.resigned), (2, 4, 4, 0));
    }

    #[tokio::test]
    async fn a_send_that_was_lost_is_signed_again_once_its_blockhash_expired() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(4 * 960);
        let (node, url) = node(buffer).await;
        {
            let mut n = node.lock().unwrap();
            n.create(&payer.pubkey(), build.len(), &[]);
            // The first write never reaches a node, and no answer comes; a blockhash lives 4 blocks.
            n.lose_sends = 1;
            n.valid_for = 4;
        }
        let chain = Chain::with_timeout(&url, ms(100)).unwrap();
        let mut tally = Tally::default();
        job(&chain, &payer, buffer, &build, quick()).fill(&mut tally).await.unwrap();
        let n = node.lock().unwrap();
        assert_eq!(n.program_bytes(), build);
        assert_eq!((n.duplicates, n.landed_writes), (0, 4));
        assert_eq!((tally.slowed, tally.resigned, tally.sent, tally.landed), (1, 1, 5, 4));
    }

    #[tokio::test]
    async fn writes_that_are_taken_and_never_land_end_the_run_after_the_stall_time() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(3 * 960);
        let (node, url) = node(buffer).await;
        {
            let mut n = node.lock().unwrap();
            n.create(&payer.pubkey(), build.len(), &[]);
            // What a cluster does with a write its payer cannot pay for: the RPC takes it, and
            // it never lands. Signed again after every expiry, it is taken again.
            n.drop_sends = usize::MAX;
            n.valid_for = 4;
        }
        let chain = Chain::with_timeout(&url, ms(500)).unwrap();
        let pace = Pace { stall: ms(300), ..quick() };
        let mut tally = Tally::default();
        let run = tokio::time::timeout(Duration::from_secs(10), job(&chain, &payer, buffer, &build, pace).fill(&mut tally)).await;
        let e = run.expect("the run went on although no write ever landed").unwrap_err().to_string();
        assert!(e.contains("no write was seen to land") && e.contains("sent and not confirmed"), "{e}");
        // The writes were taken and signed again, more than once each, and that did not count.
        assert!(tally.sent > 3 && tally.resigned >= 3 && tally.landed == 0, "{tally:?}");
        assert_eq!(node.lock().unwrap().landed_writes, 0);
    }

    #[tokio::test]
    async fn a_payer_that_cannot_pay_for_the_writes_is_told_before_anything_is_sent() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(3 * 960);
        let (node, url) = node(buffer).await;
        // One write costs 5,000 lamports and 2,670 units at 100,000 micro-lamports: 5,267. The
        // payer has to keep the rent-exempt minimum of an account (890,880 at the mock's rent).
        let enough = 890_880 + 3 * 5_267;
        {
            let mut n = node.lock().unwrap();
            n.create(&payer.pubkey(), build.len(), &[]);
            n.balance = enough - 1;
        }
        let chain = Chain::with_timeout(&url, ms(500)).unwrap();
        let e = job(&chain, &payer, buffer, &build, quick()).fill(&mut Tally::default()).await.unwrap_err().to_string();
        assert!(e.contains("the 3 write(s) still to send need 0.000906681 SOL") && e.contains("5267 lamports each"), "{e}");
        assert!(e.contains("Send it at least 0.000000001 SOL"), "{e}");
        assert_eq!(node.lock().unwrap().accepted, 0);
        // With that lamport it starts.
        node.lock().unwrap().balance = enough;
        job(&chain, &payer, buffer, &build, quick()).fill(&mut Tally::default()).await.unwrap();
        assert_eq!(node.lock().unwrap().landed_writes, 3);
    }

    #[tokio::test]
    async fn a_write_whose_blockhash_expired_is_signed_again() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(6 * 960);
        let (node, url) = node(buffer).await;
        {
            let mut n = node.lock().unwrap();
            n.create(&payer.pubkey(), build.len(), &[]);
            // The first three writes are accepted and never land; a blockhash lives 4 blocks.
            n.drop_sends = 3;
            n.valid_for = 4;
        }
        let chain = Chain::with_timeout(&url, ms(500)).unwrap();
        let mut tally = Tally::default();
        job(&chain, &payer, buffer, &build, quick()).fill(&mut tally).await.unwrap();
        let n = node.lock().unwrap();
        assert_eq!(n.program_bytes(), build);
        assert_eq!((tally.resigned, tally.sent, tally.landed, n.landed_writes), (3, 9, 6, 6));
    }

    #[tokio::test]
    async fn a_write_that_fails_on_chain_stops_the_run() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(3 * 960);
        let (node, url) = node(buffer).await;
        {
            let mut n = node.lock().unwrap();
            n.create(&payer.pubkey(), build.len(), &[]);
            n.fail_writes = true;
        }
        let chain = Chain::with_timeout(&url, ms(500)).unwrap();
        let e = job(&chain, &payer, buffer, &build, quick()).fill(&mut Tally::default()).await.unwrap_err().to_string();
        assert!(e.contains("failed on-chain") && e.contains("IncorrectAuthority"), "{e}");
    }

    #[tokio::test]
    async fn a_node_that_only_says_slow_down_ends_the_run_after_the_stall_time() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(3 * 960);
        let (node, url) = node(buffer).await;
        {
            let mut n = node.lock().unwrap();
            n.create(&payer.pubkey(), build.len(), &[]);
            n.throttle_all = true;
        }
        let chain = Chain::with_timeout(&url, ms(500)).unwrap();
        let pace = Pace { stall: ms(300), ..quick() };
        let mut tally = Tally::default();
        let e = job(&chain, &payer, buffer, &build, pace).fill(&mut tally).await.unwrap_err().to_string();
        assert!(e.contains("no write was seen to land") && e.contains("Too many requests"), "{e}");
        assert!(tally.slowed >= 3 && tally.sent == 0, "{tally:?}");
        assert_eq!(node.lock().unwrap().landed_writes, 0);
    }

    #[tokio::test]
    async fn used_up_credits_end_the_run_at_once() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(3 * 960);
        let (node, url) = node(buffer).await;
        {
            let mut n = node.lock().unwrap();
            n.create(&payer.pubkey(), build.len(), &[]);
            n.throttle_all = true;
            n.throttle = Throttle::MaxUsage;
        }
        let chain = Chain::with_timeout(&url, ms(500)).unwrap();
        let started = Instant::now();
        let e = job(&chain, &payer, buffer, &build, quick()).fill(&mut Tally::default()).await.unwrap_err().to_string();
        assert!(e.contains("credits are used up") && e.contains("it resumes"), "{e}");
        assert!(started.elapsed() < Duration::from_secs(5), "it waited for the stall time");
    }

    #[tokio::test]
    async fn stopped_part_way_and_run_again_it_continues() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(12 * 960);
        let (node, url) = node(buffer).await;
        node.lock().unwrap().create(&payer.pubkey(), build.len(), &[]);
        let chain = Chain::with_timeout(&url, ms(500)).unwrap();
        // One write every 20 ms, and the run is dropped after 130 ms: a kill in the middle.
        let slow = Pace { gap: ms(20), ..quick() };
        let killed = tokio::time::timeout(ms(130), job(&chain, &payer, buffer, &build, slow).fill(&mut Tally::default())).await;
        assert!(killed.is_err(), "the first run was meant to be cut short");
        let before = node.lock().unwrap().landed_writes;
        assert!(before > 0 && before < 12, "{before} writes landed before the stop");

        let mut tally = Tally::default();
        job(&chain, &payer, buffer, &build, quick()).fill(&mut tally).await.unwrap();
        let n = node.lock().unwrap();
        assert_eq!(n.program_bytes(), build);
        assert_eq!(tally.sent as usize, 12 - before);
        assert_eq!(n.landed_writes, 12);
    }

    #[tokio::test]
    async fn a_buffer_that_a_lagging_node_does_not_show_yet_is_asked_for_again() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(3 * 960);
        let (node, url) = node(buffer).await;
        {
            let mut n = node.lock().unwrap();
            n.create(&payer.pubkey(), build.len(), &[]);
            n.lagging_reads = 2;
        }
        let chain = Chain::with_timeout(&url, ms(500)).unwrap();
        job(&chain, &payer, buffer, &build, quick()).fill(&mut Tally::default()).await.unwrap();
        assert_eq!(node.lock().unwrap().program_bytes(), build);
        // A buffer that never shows is an error, and nothing is sent to it.
        let (node, url) = self::node(buffer).await;
        let chain = Chain::with_timeout(&url, ms(500)).unwrap();
        let e = job(&chain, &payer, buffer, &build, quick()).fill(&mut Tally::default()).await.unwrap_err().to_string();
        assert!(e.contains("does not exist"), "{e}");
        assert_eq!(node.lock().unwrap().accepted, 0);
    }

    #[tokio::test]
    async fn another_keys_buffer_is_refused_before_anything_is_sent() {
        let (payer, buffer) = (Keypair::new(), key(9));
        let build = program(3 * 960);
        let (node, url) = node(buffer).await;
        node.lock().unwrap().create(&key(7), build.len(), &[]);
        let chain = Chain::with_timeout(&url, ms(500)).unwrap();
        let e = job(&chain, &payer, buffer, &build, quick()).fill(&mut Tally::default()).await.unwrap_err().to_string();
        assert!(e.contains("not the deployer"), "{e}");
        assert_eq!(node.lock().unwrap().accepted, 0);
    }

    // ---- the command, end to end against the mock node ----------------------------------------

    fn keypair_file(dir: &std::path::Path, name: &str, key: &Keypair) -> PathBuf {
        use std::os::unix::fs::PermissionsExt;
        let path = dir.join(name);
        std::fs::write(&path, serde_json::to_string(&key.to_bytes().to_vec()).unwrap()).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        path
    }

    /// `write-buffer` for a buffer that does not exist: the account the node ends up with.
    async fn created(mode: DeployMode) -> (Address, (Address, u64, Vec<u8>), Vec<u8>, Value) {
        let dir = std::env::temp_dir().join(format!("hd-devstack-test-{}-{}", std::process::id(), mode_name(mode)));
        std::fs::create_dir_all(&dir).unwrap();
        let mut elf = program(5 * 960 + 77);
        elf[0..4].copy_from_slice(b"\x7fELF");
        elf[4] = 2;
        elf[5] = 1;
        elf[48..52].copy_from_slice(&0u32.to_le_bytes());
        std::fs::write(dir.join("p.so"), &elf).unwrap();
        let (payer, buffer_key) = (Keypair::new(), Keypair::new());
        let (node, url) = node(buffer_key.pubkey()).await;
        write_buffer(WriteBufferOpts {
            cluster: Cluster::Localnet,
            rpc: url,
            so: dir.join("p.so"),
            buffer: keypair_file(&dir, "buffer.json", &buffer_key),
            authority: keypair_file(&dir, "deployer.json", &payer),
            mode,
            max_len: 8_000,
            rate: 500.0,
            cu_price: 100_000,
            yes: false,
            json: Some(dir.join("out.json")),
        })
        .await
        .unwrap();
        let summary: Value = serde_json::from_str(&std::fs::read_to_string(dir.join("out.json")).unwrap()).unwrap();
        std::fs::remove_dir_all(&dir).unwrap();
        let account = node.lock().unwrap().account.clone().unwrap();
        (payer.pubkey(), account, elf, summary)
    }

    async fn creates_like_the_cli(mode: DeployMode, rent_len: u64) {
        let (deployer, (owner, lamports, data), elf, summary) = created(mode).await;
        // Owner, size, state and authority are what `solana program deploy` creates…
        assert_eq!(owner, BPF_UPGRADEABLE_LOADER_ID);
        assert_eq!(data.len(), 37 + elf.len());
        assert_eq!(decode_held(Some((owner, data, lamports))), Held::Buffer { authority: Some(deployer), lamports, bytes: elf.clone() });
        // …and so are the lamports, which depend on the mode (the mock's rent is 6,960 a byte).
        assert_eq!(lamports, (rent_len + 128) * 6_960);
        assert_eq!(summary["created"], json!(true));
        assert_eq!(summary["buffer_lamports"], json!(lamports));
        assert_eq!((&summary["chunks_total"], &summary["chunks_left"], &summary["verified"]), (&json!(6), &json!(0), &json!(true)));
        assert_eq!((&summary["transactions_landed"], &summary["chunks_already_written"]), (&json!(6), &json!(0)));
        // The creation has two signatures and each of the six writes one.
        assert_eq!(summary["fees_lamports"], json!(2 * 5_000 + 6 * 5_000));
    }

    #[tokio::test]
    async fn a_fresh_deploys_buffer_holds_the_programdata_rent() {
        creates_like_the_cli(DeployMode::Fresh, 45 + 8_000).await;
    }

    #[tokio::test]
    async fn an_upgrades_buffer_holds_the_rent_of_45_bytes_plus_the_build() {
        creates_like_the_cli(DeployMode::Upgrade, 45 + 5 * 960 + 77).await;
    }

    #[tokio::test]
    async fn a_proposals_buffer_holds_its_own_rent() {
        creates_like_the_cli(DeployMode::Buffer, 37 + 5 * 960 + 77).await;
    }
}
