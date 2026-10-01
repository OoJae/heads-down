//! Demo tools for filming the program's guarantees.
//!
//! * `hd-crank replay --signature <landed dig tx>`: rebuilds that dig's Secp256r1SigVerify
//!   instruction (the phone's exact signature, key and digest) and its 20-byte entries, and
//!   resubmits them with a fresh blockhash in the current round. The program refuses the old
//!   heartbeat: `RigSkipped(StaleHeartbeat)`. The tool only ever replays **stale** heartbeats
//!   (every counter must already be at or below the rig's on-chain `hb_counter`), never lease
//!   reuses and never ORE checkpoints, so a replay can never deploy anything.
//! * `hd-crank decode <signature>`: prints the transaction's heads_down events with error and
//!   reason names, one line each (or JSON), for on-screen captions.

use serde_json::{json, Value};
use solana_address::Address;
use solana_instruction::Instruction;
use solana_signer::Signer;

use crate::hd::{self, DigEntry, HdEvent, Rig, RigAccounts, RigState};
use crate::rpc::{FetchedInstruction, FullTransaction};
use crate::tx::{self, TxError};

/// Why a transaction cannot be replayed.
#[derive(Debug, PartialEq, Eq, thiserror::Error)]
pub enum ReplayError {
    /// No heads_down `dig` in the transaction.
    #[error("no heads_down dig instruction in this transaction")]
    NoDig,
    /// The dig's data or accounts do not follow the contract.
    #[error("malformed dig instruction: {0}")]
    Malformed(&'static str),
    /// A `hb_ix` does not point at a valid Secp256r1SigVerify instruction entry.
    #[error("entry {0}: its hb_ix does not name a valid secp256r1 entry")]
    BadPrecompile(usize),
    /// Only lease reuses (hb_ix = 0xFF) in the selection: nothing signed to replay.
    #[error("no fresh-heartbeat entry to replay (lease reuses are never replayed)")]
    NothingToReplay,
    /// A heartbeat is not stale yet: replaying it could really dig.
    #[error("rig {rig}: counter {counter} is above the on-chain hb_counter {on_chain}; refusing (a replay must be stale)")]
    NotStale {
        /// Rig.
        rig: Address,
        /// Heartbeat counter.
        counter: u64,
        /// `rig.hb_counter`.
        on_chain: u64,
    },
    /// More heartbeats than one precompile instruction holds.
    #[error("{0} heartbeats selected; replay at most 8 (use --rig)")]
    TooMany(usize),
}

/// One signed heartbeat recovered from a landed dig.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ReplayRig {
    /// The rig's four accounts, as the dig passed them.
    pub accounts: RigAccounts,
    /// The original entry (counter, round, lease; its `hb_ix` / `hb_sig_index` are remapped).
    pub entry: DigEntry,
    /// The phone's low-S signature.
    pub sig: [u8; 64],
    /// The key the precompile verified.
    pub pubkey: [u8; 33],
    /// The 32-byte digest the phone signed.
    pub digest: [u8; 32],
}

/// The dig in a landed transaction: every rig with its entry, and the signed heartbeats.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LandedDig {
    /// `(accounts, entry)` in the dig's order.
    pub rigs: Vec<(RigAccounts, DigEntry)>,
    /// Entries with a fresh heartbeat, resolved to their precompile entry.
    pub heartbeats: Vec<ReplayRig>,
}

/// Find the `dig` in `instructions` and resolve every fresh-heartbeat entry.
pub fn extract_dig(program_id: &Address, instructions: &[FetchedInstruction]) -> Result<LandedDig, ReplayError> {
    let dig = instructions
        .iter()
        .find(|ix| ix.program_id == *program_id && ix.data.first() == Some(&hd::IX_DIG))
        .ok_or(ReplayError::NoDig)?;
    let entries = DigEntry::decode_list(&dig.data[1..]).ok_or(ReplayError::Malformed("entries"))?;
    if dig.accounts.len() != hd::DIG_FIXED_ACCOUNTS + hd::DIG_ACCOUNTS_PER_RIG * entries.len() {
        return Err(ReplayError::Malformed("account count"));
    }
    let mut rigs = Vec::with_capacity(entries.len());
    let mut heartbeats = Vec::new();
    for (i, e) in entries.into_iter().enumerate() {
        let base = hd::DIG_FIXED_ACCOUNTS + hd::DIG_ACCOUNTS_PER_RIG * i;
        let a = &dig.accounts[base..base + hd::DIG_ACCOUNTS_PER_RIG];
        let accounts = RigAccounts { rig: a[0], authority: a[1], automation: a[2], miner: a[3] };
        rigs.push((accounts, e));
        if e.hb_ix == hd::HB_REUSE_LEASE {
            continue;
        }
        let pix = instructions.get(usize::from(e.hb_ix)).ok_or(ReplayError::BadPrecompile(i))?;
        if pix.program_id != hd::SECP256R1_PROGRAM_ID {
            return Err(ReplayError::BadPrecompile(i));
        }
        let parsed = p256_introspect::Secp256r1Instruction::parse(&pix.data, u16::from(e.hb_ix))
            .map_err(|_| ReplayError::BadPrecompile(i))?;
        let entry = parsed.entry(e.hb_sig_index).map_err(|_| ReplayError::BadPrecompile(i))?;
        let digest: [u8; 32] = entry.message.try_into().map_err(|_| ReplayError::BadPrecompile(i))?;
        heartbeats.push(ReplayRig { accounts, entry: e, sig: *entry.signature, pubkey: *entry.public_key, digest });
    }
    Ok(LandedDig { rigs, heartbeats })
}

/// Index of the rebuilt precompile in [`replay_instructions`] (after two ComputeBudget ixs).
pub const REPLAY_P256_IX: u8 = 2;

/// `[SetComputeUnitLimit, SetComputeUnitPrice, Secp256r1SigVerify (the same signatures),
/// dig (same rigs and entries, current Round)]`. The Round must be the live
/// `Board.round_id`'s, or the whole transaction fails before any rig is looked at.
pub fn replay_instructions(
    program_id: &Address,
    cranker: &Address,
    board_round_id: u64,
    rigs: &[ReplayRig],
    cu_limit: u32,
    cu_price_micro_lamports: u64,
) -> Result<Vec<Instruction>, TxError> {
    if rigs.is_empty() {
        return Err(TxError::Empty);
    }
    let sigs: Vec<_> = rigs.iter().map(|r| (r.sig, r.pubkey, r.digest)).collect();
    let pairs: Vec<(RigAccounts, DigEntry)> = rigs
        .iter()
        .enumerate()
        .map(|(i, r)| {
            (
                r.accounts,
                DigEntry { hb_ix: REPLAY_P256_IX, hb_sig_index: u8::try_from(i).unwrap_or(u8::MAX), ..r.entry },
            )
        })
        .collect();
    Ok(vec![
        tx::set_compute_unit_limit(cu_limit),
        tx::set_compute_unit_price(cu_price_micro_lamports),
        tx::precompile_ix(&sigs)?,
        hd::dig_ix(program_id, cranker, &crate::ore::round_pda(board_round_id), &pairs)?,
    ])
}

/// Keep the heartbeats of `only` (all if empty) and require each to be stale against the
/// rig's current on-chain `hb_counter`.
pub fn select_stale(
    heartbeats: &[ReplayRig],
    only: &[Address],
    rigs_now: &[(Address, Rig)],
) -> Result<Vec<ReplayRig>, ReplayError> {
    let picked: Vec<ReplayRig> =
        heartbeats.iter().filter(|h| only.is_empty() || only.contains(&h.accounts.rig)).copied().collect();
    if picked.is_empty() {
        return Err(ReplayError::NothingToReplay);
    }
    if picked.len() > tx::MAX_SIGS_PER_PRECOMPILE {
        return Err(ReplayError::TooMany(picked.len()));
    }
    for h in &picked {
        let on_chain = rigs_now.iter().find(|(a, _)| *a == h.accounts.rig).map_or(0, |(_, r)| r.hb_counter);
        if h.entry.counter > on_chain {
            return Err(ReplayError::NotStale { rig: h.accounts.rig, counter: h.entry.counter, on_chain });
        }
    }
    Ok(picked)
}

/// The skip the program should report for a stale replay of `h` against `rig` (INTERFACE
/// §6.2 steps 2-3, assuming the rig's ORE accounts are intact).
pub fn expected_skip(rig: &Rig, entry: &DigEntry, board_round_id: u64) -> u32 {
    match rig.state {
        RigState::Frozen => 14,
        RigState::Armed | RigState::Down | RigState::Cooling => {
            if entry.round_id > board_round_id || entry.lease_rounds == 0 {
                6
            } else {
                7
            }
        }
        _ => 13,
    }
}

fn short(a: &Address) -> String {
    let s = a.to_string();
    if s.len() > 12 {
        format!("{}…{}", &s[..4], &s[s.len() - 4..])
    } else {
        s
    }
}

fn thousands(v: u64) -> String {
    let s = v.to_string();
    let mut out = String::with_capacity(s.len() + s.len() / 3);
    for (i, c) in s.chars().enumerate() {
        if i > 0 && (s.len() - i).is_multiple_of(3) {
            out.push(',');
        }
        out.push(c);
    }
    out
}

/// One caption line for an event.
pub fn caption(e: &HdEvent) -> String {
    match e {
        HdEvent::RigDug { rig, round_id, lamports, mask, ema_ev } => format!(
            "RigDug              rig {}  round {round_id}  {} lamports on {} squares (mask {mask:#09x})  ema_ev {} lamports/ORE",
            short(rig),
            thousands(*lamports),
            mask.count_ones(),
            thousands(*ema_ev)
        ),
        HdEvent::RigSkipped { rig, round_id, error } => {
            format!("RigSkipped          rig {}  round {round_id}  {} ({error:#x})", short(rig), hd::error_name(*error))
        }
        HdEvent::ShiftArmed { rig, shift_id } => format!("ShiftArmed          rig {}  shift {shift_id}", short(rig)),
        HdEvent::ShiftEnded { rig, shift_id, dark_rounds, rounds_dug, lamports, reason } => format!(
            "ShiftEnded          rig {}  shift {shift_id}  {} ({reason})  dark {dark_rounds}  dug {rounds_dug}  debit {} lamports",
            short(rig),
            hd::reason_name(*reason),
            thousands(*lamports)
        ),
        HdEvent::SeekerVerified { rig, sgt_mint, member_number } => {
            format!("SeekerVerified      rig {}  sgt {}  member #{member_number}", short(rig), short(sgt_mint))
        }
        HdEvent::RigRegistered { rig, authority, tier, attestation_level } => format!(
            "RigRegistered       rig {}  wallet {}  tier {tier}  attestation {attestation_level}",
            short(rig),
            short(authority)
        ),
        HdEvent::RigClosed { rig } => format!("RigClosed           rig {}", short(rig)),
        HdEvent::HeartbeatsRecorded { rig, round_id, dark_rounds_added } => {
            format!("HeartbeatsRecorded  rig {}  round {round_id}  +{dark_rounds_added} dark rounds", short(rig))
        }
        HdEvent::ShiftBroken { rig, shift_id, reason } => {
            format!("ShiftBroken         rig {}  shift {shift_id}  {} ({reason})", short(rig), hd::reason_name(*reason))
        }
        HdEvent::ShiftEndedV2 { rig, shift_id, dark_rounds, rounds_dug, lamports, reason, start_round, end_round, mode } => {
            format!(
                "ShiftEnded          rig {}  shift {shift_id}  {} ({reason})  {}  rounds {start_round}..{end_round}  dark {dark_rounds}  dug {rounds_dug}  debit {} lamports",
                short(rig),
                hd::reason_name(*reason),
                hd::mode_name(*mode),
                thousands(*lamports)
            )
        }
        HdEvent::Other { tag, body } => format!("Event tag {tag}       {} bytes", body.len() + 1),
    }
}

/// An event as JSON (u64 as decimal strings, names spelled out).
pub fn event_json(e: &HdEvent) -> Value {
    let s = |v: u64| v.to_string();
    match e {
        HdEvent::RigDug { rig, round_id, lamports, mask, ema_ev } => json!({
            "event": "RigDug", "rig": rig.to_string(), "round_id": s(*round_id), "lamports": s(*lamports),
            "mask": mask, "squares": mask.count_ones(), "ema_ev": s(*ema_ev)
        }),
        HdEvent::RigSkipped { rig, round_id, error } => json!({
            "event": "RigSkipped", "rig": rig.to_string(), "round_id": s(*round_id), "error": error,
            "error_name": hd::error_name(*error)
        }),
        HdEvent::ShiftArmed { rig, shift_id } => json!({ "event": "ShiftArmed", "rig": rig.to_string(), "shift_id": s(*shift_id) }),
        HdEvent::ShiftEnded { rig, shift_id, dark_rounds, rounds_dug, lamports, reason } => json!({
            "event": "ShiftEnded", "rig": rig.to_string(), "shift_id": s(*shift_id), "dark_rounds": s(*dark_rounds),
            "rounds_dug": s(*rounds_dug), "lamports": s(*lamports), "reason": reason, "reason_name": hd::reason_name(*reason)
        }),
        HdEvent::SeekerVerified { rig, sgt_mint, member_number } => json!({
            "event": "SeekerVerified", "rig": rig.to_string(), "sgt_mint": sgt_mint.to_string(), "member_number": s(*member_number)
        }),
        HdEvent::RigRegistered { rig, authority, tier, attestation_level } => json!({
            "event": "RigRegistered", "rig": rig.to_string(), "authority": authority.to_string(), "tier": tier,
            "attestation_level": attestation_level
        }),
        HdEvent::RigClosed { rig } => json!({ "event": "RigClosed", "rig": rig.to_string() }),
        HdEvent::HeartbeatsRecorded { rig, round_id, dark_rounds_added } => json!({
            "event": "HeartbeatsRecorded", "rig": rig.to_string(), "round_id": s(*round_id), "dark_rounds_added": s(*dark_rounds_added)
        }),
        HdEvent::ShiftBroken { rig, shift_id, reason } => json!({
            "event": "ShiftBroken", "rig": rig.to_string(), "shift_id": s(*shift_id), "reason": reason,
            "reason_name": hd::reason_name(*reason)
        }),
        HdEvent::ShiftEndedV2 { rig, shift_id, dark_rounds, rounds_dug, lamports, reason, start_round, end_round, mode } => json!({
            "event": "ShiftEndedV2", "rig": rig.to_string(), "shift_id": s(*shift_id), "dark_rounds": s(*dark_rounds),
            "rounds_dug": s(*rounds_dug), "lamports": s(*lamports), "reason": reason, "reason_name": hd::reason_name(*reason),
            "start_round": s(*start_round), "end_round": s(*end_round), "mode": mode, "mode_name": hd::mode_name(*mode)
        }),
        HdEvent::Other { tag, body } => json!({ "event": "Other", "tag": tag, "hex": hex::encode(body) }),
    }
}

/// The lines `decode` prints for a fetched transaction.
pub fn decode_lines(program_id: &Address, sig: &str, t: &FullTransaction) -> Vec<String> {
    let status = match &t.err {
        None => "ok".to_string(),
        Some(e) => format!("FAILED {e}"),
    };
    let mut out = vec![format!(
        "tx {sig}  slot {}  {status}  fee {} lamports  {} CU",
        t.slot,
        thousands(t.fee),
        t.compute_units.map_or("?".into(), thousands)
    )];
    let evs = hd::events_from_logs(program_id, &t.logs);
    if evs.is_empty() {
        out.push("  (no heads_down events)".into());
    }
    out.extend(evs.iter().map(|e| format!("  {}", caption(e))));
    out
}

/// `hd-crank decode <signature>`.
pub async fn decode(rpc: &crate::rpc::RpcClient, program_id: &Address, sig: &str, as_json: bool) -> anyhow::Result<()> {
    let t = rpc.get_transaction_full(sig).await?.ok_or_else(|| anyhow::anyhow!("transaction {sig} not found (confirmed)"))?;
    if as_json {
        let evs: Vec<Value> = hd::events_from_logs(program_id, &t.logs).iter().map(event_json).collect();
        println!(
            "{}",
            json!({ "signature": sig, "slot": t.slot, "ok": t.err.is_none(), "err": t.err, "fee": t.fee,
                    "compute_units": t.compute_units, "events": evs })
        );
    } else {
        for l in decode_lines(program_id, sig, &t) {
            println!("{l}");
        }
    }
    Ok(())
}

/// Options for [`replay`].
pub struct ReplayOpts {
    /// The landed dig to replay.
    pub signature: String,
    /// Only these rigs (all fresh-heartbeat entries if empty).
    pub rigs: Vec<Address>,
    /// Simulate only.
    pub dry_run: bool,
    /// Priority fee.
    pub cu_price_micro_lamports: u64,
}

/// `hd-crank replay --signature <sig>`: see the module docs.
pub async fn replay(
    rpc: &crate::rpc::RpcClient,
    program_id: &Address,
    key: &crate::keys::CrankKey,
    o: ReplayOpts,
) -> anyhow::Result<()> {
    let t = rpc
        .get_transaction_full(&o.signature)
        .await?
        .ok_or_else(|| anyhow::anyhow!("transaction {} not found (confirmed)", o.signature))?;
    let landed = extract_dig(program_id, &t.instructions)?;
    let rig_addrs: Vec<Address> = landed.heartbeats.iter().map(|h| h.accounts.rig).collect();
    let accs = rpc.get_multiple_accounts(&rig_addrs).await?;
    let rigs_now: Vec<(Address, Rig)> = rig_addrs
        .iter()
        .zip(accs)
        .filter_map(|(a, acc)| acc.and_then(|acc| Rig::decode(program_id, &acc.owner, &acc.data).ok()).map(|r| (*a, r)))
        .collect();
    let picked = select_stale(&landed.heartbeats, &o.rigs, &rigs_now)?;
    let board_acc = rpc.get_account(&crate::ore::BOARD_ADDRESS).await?.ok_or_else(|| anyhow::anyhow!("ORE Board missing"))?;
    let board = crate::ore::Board::decode(&board_acc.owner, &board_acc.data).map_err(|e| anyhow::anyhow!("Board: {e}"))?;
    println!(
        "replaying dig {} (slot {}): {} signed heartbeat(s), {} lease reuse(s) left out",
        o.signature,
        t.slot,
        picked.len(),
        landed.rigs.iter().filter(|(_, e)| e.hb_ix == hd::HB_REUSE_LEASE).count()
    );
    for h in &picked {
        let rig = rigs_now.iter().find(|(a, _)| *a == h.accounts.rig).map(|(_, r)| r.clone()).unwrap_or_default();
        let want = expected_skip(&rig, &h.entry, board.round_id);
        println!(
            "  rig {}  heartbeat #{} signed for round {} (lease {})  on-chain hb_counter {}  state {}  expect RigSkipped({})",
            h.accounts.rig,
            h.entry.counter,
            h.entry.round_id,
            h.entry.lease_rounds,
            rig.hb_counter,
            rig.state.name(),
            hd::error_name(want)
        );
    }
    let cranker = key.keypair().pubkey();
    let ixs = replay_instructions(program_id, &cranker, board.round_id, &picked, 400_000, o.cu_price_micro_lamports)?;
    let (bh, lvbh) = rpc.get_latest_blockhash().await?;
    let signed = tx::sign_legacy(&ixs, key.keypair(), bh)?;
    let wire = tx::serialize(&signed)?;
    println!("resubmitting the same signed bytes with a fresh blockhash, in ORE round {}", board.round_id);
    let sim = rpc.simulate_transaction(&wire).await?;
    if let Some(err) = &sim.err {
        anyhow::bail!("simulation failed: {err} (logs: {:?})", sim.logs.iter().rev().take(5).collect::<Vec<_>>());
    }
    let sim_events = hd::events_from_logs(program_id, &sim.logs);
    if o.dry_run {
        println!("dry run (simulated only):");
        for e in &sim_events {
            println!("  {}", caption(e));
        }
        return Ok(());
    }
    if sim_events.iter().any(|e| matches!(e, HdEvent::RigDug { .. })) {
        anyhow::bail!("refusing to send: the simulation shows a RigDug (the heartbeat was not stale)");
    }
    let sig = signed.signatures.first().map(ToString::to_string).unwrap_or_default();
    let submitter = crate::sender::Submitter::rpc(rpc.clone());
    submitter.send(&wire).await?;
    match submitter.confirm(&sig, &wire, lvbh, crate::sender::ConfirmPolicy::default()).await {
        crate::sender::Outcome::Landed { slot, err } => {
            println!("tx {sig} landed in slot {slot}{}", err.map(|e| format!(" with error {e}")).unwrap_or_default());
            let t = rpc.get_transaction_full(&sig).await?;
            let logs = t.map(|t| t.logs).unwrap_or(sim.logs);
            for e in hd::events_from_logs(program_id, &logs) {
                println!("  {}", caption(&e));
            }
            Ok(())
        }
        other => anyhow::bail!("replay {sig} did not land: {other:?}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn number_formatting_and_captions() {
        assert_eq!(thousands(0), "0");
        assert_eq!(thousands(999), "999");
        assert_eq!(thousands(1_000), "1,000");
        assert_eq!(thousands(653_163_071), "653,163,071");
        let rig = Address::new_from_array([4; 32]);
        let c = caption(&HdEvent::RigSkipped { rig, round_id: 5, error: 7 });
        assert!(c.contains("StaleHeartbeat (0x7)"), "{c}");
        let c = caption(&HdEvent::ShiftBroken { rig, shift_id: 2, reason: 1 });
        assert!(c.contains("pickup (1)"), "{c}");
        let j = event_json(&HdEvent::RigDug { rig, round_id: 5, lamports: 1_000_000, mask: 0b111, ema_ev: 9 });
        assert_eq!(j["squares"], 3);
        assert_eq!(j["lamports"], "1000000");
    }

    #[test]
    fn expected_skip_follows_the_state_machine() {
        let e = DigEntry { hb_ix: 2, hb_sig_index: 0, counter: 3, round_id: 10, lease_rounds: 1 };
        let mut r = Rig { hb_counter: 3, state: RigState::Down, ..Rig::default() };
        assert_eq!(expected_skip(&r, &e, 11), 7);
        r.state = RigState::Frozen;
        assert_eq!(expected_skip(&r, &e, 11), 14);
        r.state = RigState::Idle;
        assert_eq!(expected_skip(&r, &e, 11), 13);
        r.state = RigState::Cooling;
        assert_eq!(expected_skip(&r, &e, 9), 6, "a heartbeat from the future");
    }
}
