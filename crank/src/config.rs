//! Configuration: a TOML file, then environment overrides for **every** setting.
//!
//! Secrets never live in the TOML: the fee-payer keypair is a **path** (CLI or env), and
//! the Helius API key is read **only** from `HELIUS_API_KEY` and substituted into URLs that
//! contain the `{HELIUS_API_KEY}` placeholder. A TOML URL that embeds an `api-key=` value
//! is refused.
//!
//! ## Environment overrides
//!
//! Every setting has an environment variable: `HD_CRANK_` + the setting's path in upper case,
//! with `_` between the section and the field. `[dig] max_rigs_per_tx` is
//! `HD_CRANK_DIG_MAX_RIGS_PER_TX`, `[stack] max_lamports_per_table` is
//! `HD_CRANK_STACK_MAX_LAMPORTS_PER_TABLE`, the top-level `log_json` is `HD_CRANK_LOG_JSON`,
//! `[dig.cu_estimate] per_rig` is `HD_CRANK_DIG_CU_ESTIMATE_PER_RIG`. [`env_settings`] lists
//! them all (`hd-crank config --env` prints the list). Values: booleans as `true` / `false`
//! (also `1` / `0`, `yes` / `no`, `on` / `off`), numbers in decimal, lists comma-separated, an
//! empty value clears an optional setting. A value that does not parse is an error that names
//! the variable and never echoes the value.
//!
//! | Env | Meaning |
//! |---|---|
//! | `HD_CRANK_<SECTION>_<FIELD>` | overrides that setting (see above) |
//! | `HD_CRANK_KEYPAIR` | alias of `HD_CRANK_KEYPAIR_PATH` |
//! | `HD_CRANK_TX_FORMAT` | alias of `HD_CRANK_DIG_TX_FORMAT` (`legacy` / `v0` / `v1`) |
//! | `HD_CRANK_CONFIG` | the TOML file (read by the CLI, not a setting) |
//! | `HELIUS_API_KEY` | fills `{HELIUS_API_KEY}` in any URL; never logged |
//!
//! Precedence: defaults, then the TOML file, then the environment, then `--keypair`.

use std::path::PathBuf;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use solana_address::Address;

use crate::hd;
use crate::intake::IntakeConfig;
use crate::ore;
use crate::planner::Policy;
use crate::ratelimit::Quota;
use crate::rpc::redact_url;
use crate::tx::{CheckinCu, CuEstimate, TxFormat};

/// Placeholder substituted from `HELIUS_API_KEY`.
pub const HELIUS_PLACEHOLDER: &str = "{HELIUS_API_KEY}";
/// Prefix of every setting's environment variable.
pub const ENV_PREFIX: &str = "HD_CRANK_";
/// `HD_CRANK_*` variables that are not settings: the config path (read by the CLI) and the
/// key material the container entrypoint consumes before it starts the binary.
pub const RESERVED_ENV: [&str; 2] = ["HD_CRANK_CONFIG", "HD_CRANK_KEYPAIR_JSON"];
/// Legacy short names, kept working: `(alias, canonical variable)`.
pub const ENV_ALIASES: [(&str, &str); 2] =
    [("HD_CRANK_KEYPAIR", "HD_CRANK_KEYPAIR_PATH"), ("HD_CRANK_TX_FORMAT", "HD_CRANK_DIG_TX_FORMAT")];
/// Largest `chain_poll_secs`. When the WebSocket goes silent the HTTP poll is the only thing
/// that moves the slot, and `/healthz` reports `degraded` once the slot is older than
/// [`crate::intake::STALE_CHAIN_AFTER`] (30 s). With an interval of 20 s or less the slot
/// stays inside 30 s as long as every poll answers within [`crate::rpc::RPC_TIMEOUT`] (10 s)
/// in all. A poll is two calls, each with that timeout: an RPC so slow that the two take
/// longer together, or a poll that fails, can still show as `degraded` until the next poll
/// that works (at 15 s, one failed poll is enough).
pub const MAX_CHAIN_POLL_SECS: u64 = crate::intake::STALE_CHAIN_AFTER.as_secs() - crate::rpc::RPC_TIMEOUT.as_secs();

/// Top-level config.
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields, default)]
pub struct Config {
    /// JSON-RPC HTTP endpoint (may contain `{HELIUS_API_KEY}`).
    pub rpc_url: String,
    /// WebSocket endpoint; derived from `rpc_url` when absent.
    pub ws_url: Option<String>,
    /// Read commitment.
    pub commitment: String,
    /// Fee-payer keypair path.
    pub keypair_path: Option<PathBuf>,
    /// heads_down program id.
    pub program_id: String,
    /// Intake / health / metrics listen address.
    pub listen: String,
    /// Where the crank keeps its lookup-table list and the per-table Stack spend.
    pub state_dir: PathBuf,
    /// JSON logs.
    pub log_json: bool,
    /// On SIGTERM / SIGINT: stop taking work, then wait this long for transactions in flight
    /// (a BREAK that was acknowledged, a check-in that was sent) before exiting.
    pub shutdown_grace_secs: u64,
    /// How often the chain watcher re-reads the slot and ORE's Board, Treasury, Config and
    /// Round over HTTP (two calls), beside the WebSocket stream. 1..=[`MAX_CHAIN_POLL_SECS`].
    /// The stream delivers every slot; the poll covers a stream that stalled, and while it
    /// does the crank sees the chain once per interval. The dig window (`deploy_margin_slots`
    /// down to `min_slots_left`) is under 5 s long at today's slot time, so the longer the
    /// interval, the likelier a stalled stream costs that round's dig.
    pub chain_poll_secs: u64,
    /// Digging.
    pub dig: DigConfig,
    /// Heartbeat intake.
    pub intake: IntakeToml,
    /// Lookup tables.
    pub alt: AltConfig,
    /// Submit path.
    pub sender: SenderConfig,
    /// Landing phone-signed BREAK / FREEZE.
    pub signals: SignalsConfig,
    /// `record_heartbeats` for focus-only rigs.
    pub record: RecordConfig,
    /// Permissionless `end_shift`.
    pub end_shift: EndShiftConfig,
    /// Stack check-ins and settles (INTERFACE v1.2).
    pub stack: StackConfig,
    /// Permissionless cleanups: broken Focus Bonds, expired gifts.
    pub cleanup: CleanupConfig,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            rpc_url: "https://api.mainnet-beta.solana.com".into(),
            ws_url: None,
            commitment: "confirmed".into(),
            keypair_path: None,
            program_id: hd::PROGRAM_ID.to_string(),
            listen: "0.0.0.0:8787".into(),
            state_dir: PathBuf::from(".hd-crank"),
            log_json: false,
            shutdown_grace_secs: 8,
            chain_poll_secs: 5,
            dig: DigConfig::default(),
            intake: IntakeToml::default(),
            alt: AltConfig::default(),
            sender: SenderConfig::default(),
            signals: SignalsConfig::default(),
            record: RecordConfig::default(),
            end_shift: EndShiftConfig::default(),
            stack: StackConfig::default(),
            cleanup: CleanupConfig::default(),
        }
    }
}

/// The redacted view: URLs keep only scheme and host, so a `{:?}` of a config can never put
/// an API key in a log line.
impl std::fmt::Debug for Config {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Config({})", self.public_json())
    }
}

/// Landing phone-signed BREAK / FREEZE (`break_shift` / `freeze_rig`, P-256 path). The crank
/// pays one transaction signature, one secp256r1 signature and the priority fee per signal;
/// the program does not reimburse these.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, default)]
pub struct SignalsConfig {
    /// Land signals (false: the intake answers `rate_limited` and the phone keeps its record).
    pub enabled: bool,
    /// Per-rig burst.
    pub rig_burst: u32,
    /// Per-rig sustained rate.
    pub rig_per_minute: f64,
    /// Fee budget for signals, lamports per hour (a rolling bucket).
    pub max_lamports_per_hour: u64,
    /// Compute limit of a signal transaction (measured: see README).
    pub cu_limit: u32,
    /// Priority fee (micro-lamports per CU): a BREAK should land within a slot or two.
    pub cu_price_micro_lamports: u64,
    /// Simulate first; a signal the program would refuse is not sent (and not paid for).
    pub simulate: bool,
    /// Attempts with a fresh blockhash when one expires unconfirmed.
    pub max_attempts: u32,
    /// Queue length between the intake and the lander.
    pub queue: usize,
    /// Streak protection: never land a phone-signed BREAK once the rig's plan window has
    /// ended. After the window nothing is dug anyway, and a BREAK there would make
    /// `end_shift` seal a completed night as a pickup (the streak would not count it). The
    /// phone is answered `ok: false, reason: lease_invalid`. FREEZE is always landed.
    pub streak_protection: bool,
    /// Seconds before `plan_window_end_ts` from which a BREAK is no longer landed (it could
    /// reach the chain after the window ended).
    pub window_margin_secs: i64,
}

impl Default for SignalsConfig {
    fn default() -> Self {
        SignalsConfig {
            enabled: true,
            rig_burst: 4,
            rig_per_minute: 1.0,
            max_lamports_per_hour: 2_000_000,
            // Measured with the real program: 1,429 CU for a BREAK or FREEZE (fork suite).
            cu_limit: 5_000,
            cu_price_micro_lamports: 20_000,
            simulate: true,
            max_attempts: 3,
            queue: 256,
            streak_protection: true,
            window_margin_secs: 5,
        }
    }
}

impl SignalsConfig {
    /// Estimated lamports per landed signal: 2 signatures + the priority fee.
    pub fn est_fee(&self) -> u64 {
        crate::tx::fee_for(1, self.cu_limit, self.cu_price_micro_lamports, crate::tx::LAMPORTS_PER_SIGNATURE)
    }

    /// The hub's runtime knobs.
    pub fn hub(&self) -> crate::signal::SignalHubConfig {
        crate::signal::SignalHubConfig {
            enabled: self.enabled,
            rig_quota: Quota::new(self.rig_burst, self.rig_per_minute / 60.0),
            max_lamports_per_hour: self.max_lamports_per_hour,
            est_fee: self.est_fee(),
            queue: self.queue,
            max_rigs: 100_000,
        }
    }

    /// The window rule the intake and the lander apply to a BREAK.
    pub fn window_rule(&self) -> crate::heartbeat::WindowRule {
        crate::heartbeat::WindowRule { enabled: self.streak_protection, margin_secs: self.window_margin_secs }
    }
}

/// `record_heartbeats` for focus-only rigs (`plan_flags` bit 0), so their dark rounds count
/// on-chain. No deploy happens and nothing reimburses the crank: the fees are budgeted.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, default)]
pub struct RecordConfig {
    /// Record heartbeats at all.
    pub enabled: bool,
    /// Record a rig at most every N rounds (from its on-chain `lease_from_round`). With N no
    /// larger than the phone's lease, every round of the shift counts as dark.
    pub every_rounds: u64,
    /// Also record rigs whose cost gate is closed this round (off: the crank pays for them).
    pub gate_closed_rigs: bool,
    /// Seconds after a round is first seen before its record pass (the phone's heartbeat for
    /// the new round arrives first).
    pub delay_secs: u64,
    /// Rigs per transaction at most (one precompile instruction holds 8).
    pub max_rigs_per_tx: usize,
    /// Fee budget for record transactions, lamports per hour.
    pub max_lamports_per_hour: u64,
    /// Compute limit: `base + per_rig × n`.
    pub cu_base: u32,
    /// Compute per recorded rig.
    pub cu_per_rig: u32,
}

impl Default for RecordConfig {
    fn default() -> Self {
        RecordConfig {
            enabled: true,
            every_rounds: 3,
            gate_closed_rigs: false,
            delay_secs: 20,
            max_rigs_per_tx: 8,
            max_lamports_per_hour: 2_000_000,
            // Measured with the real program: ~1,500 CU per recorded rig (fork suite).
            cu_base: 3_000,
            cu_per_rig: 4_000,
        }
    }
}

impl RecordConfig {
    /// Compute estimate for record batches.
    pub fn cu_estimate(&self) -> CuEstimate {
        CuEstimate { base: self.cu_base, per_rig: self.cu_per_rig, per_checkpoint: 0 }
    }

    /// Planner policy.
    pub fn policy(&self, clock_margin_secs: i64) -> crate::planner::RecordPolicy {
        crate::planner::RecordPolicy {
            every_rounds: self.every_rounds,
            gate_closed_rigs: self.gate_closed_rigs,
            clock_margin_secs,
        }
    }
}

/// Permissionless `end_shift` for shifts past their window whose lease has been expired for
/// more than the program's 3-round grace. The
/// caller pays the ShiftLog rent (128 bytes: 1,300,480 lamports at mainnet's 5,080 lamports
/// per byte) plus the fee, and nothing reimburses it, so it is capped.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, default)]
pub struct EndShiftConfig {
    /// End stale shifts at all.
    pub enabled: bool,
    /// Seconds after `plan_window_end_ts` before the crank ends a shift (the cluster clock
    /// must be past the window; the rig's wallet can end it any time).
    pub grace_secs: i64,
    /// Shifts ended per pass at most.
    pub max_per_pass: usize,
    /// Rent + fees per day at most (a rolling bucket), in lamports.
    pub max_lamports_per_day: u64,
    /// How often to look for stale shifts.
    pub poll_secs: u64,
    /// Compute limit of an `end_shift` transaction.
    pub cu_limit: u32,
}

impl Default for EndShiftConfig {
    fn default() -> Self {
        EndShiftConfig {
            enabled: true,
            grace_secs: 60,
            max_per_pass: 4,
            max_lamports_per_day: 50_000_000,
            poll_secs: 60,
            // Measured with the real program: 5,195 CU (fork suite).
            cu_limit: 15_000,
        }
    }
}

/// Stack (INTERFACE v1.2 §11): `stack_checkin` every round for every seat of an open table,
/// then `settle_stack`. The program has no reimbursement for either: a StackTable carries no
/// crank tip and `stack_checkin` has no payer account, so the crank operator pays every
/// check-in. Both a per-table cap and an hourly budget bound that.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, default)]
pub struct StackConfig {
    /// Check seats in and settle tables at all. Off: seated rigs dig and record as usual and
    /// nobody checks them in from this crank.
    pub enabled: bool,
    /// Full `getProgramAccounts` refresh of the open tables and their pending seats, in
    /// seconds (program events and table windows opening refresh sooner).
    pub discover_secs: u64,
    /// How often the pending seats of a live round are re-read and re-planned.
    pub pass_interval_ms: u64,
    /// After a round is first seen, wait this long for every seat's heartbeat before sending
    /// a partial batch.
    pub batch_wait_ms: u64,
    /// Minimum gap between two partial batches of one table.
    pub straggler_wait_ms: u64,
    /// With this few slots left before the round's `end_slot`, send whatever is ready at once.
    pub late_slots: u64,
    /// Re-plan and re-send a check-in that is unconfirmed after this many slots.
    pub retry_after_slots: u64,
    /// Transactions per (seat, round) at most.
    pub max_attempts_per_round: u32,
    /// Seats per transaction at most (the program takes 8 per instruction; a packet holds 3
    /// verified seats legacy, 4 or more with the crank's lookup table, 8 observed).
    pub max_seats_per_tx: usize,
    /// Open tables tracked at most (largest total bonds first).
    pub max_tables: usize,
    /// Check-in transactions per pass at most.
    pub max_txs_per_pass: usize,
    /// Simulate a check-in first: sizes the CU limit, and a transaction the program would
    /// fail (the round just changed, the table was settled) is not paid for.
    pub simulate: bool,
    /// Compute estimate when not simulating: fixed part.
    pub cu_base: u32,
    /// Compute estimate: per seat whose heartbeat the check-in verifies.
    pub cu_per_verify: u32,
    /// Compute estimate: per observed seat.
    pub cu_per_observe: u32,
    /// Priority fee for check-ins and settles (micro-lamports per CU). Check-ins are
    /// fail-closed, so they outbid the dig floor.
    pub cu_price_micro_lamports: u64,
    /// Fees per table at most, in lamports (0 = unlimited). When a table's budget is spent the
    /// crank stops checking its seats in (an alert) and still settles it. The default is above
    /// what the largest table the program allows costs (8 seats x 1,440 rounds x about 7,000
    /// lamports = 0.081 SOL), so it only stops a table whose transactions keep failing.
    pub max_lamports_per_table: u64,
    /// Fees for all Stack transactions per hour at most (a rolling bucket), in lamports. One
    /// seat costs about 0.00038 SOL per hour (60 rounds x 6,300 lamports): the default covers
    /// about 26 seated rigs. Stack is fail-closed, so size this for the tables you serve.
    pub max_lamports_per_hour: u64,
    /// Add a seat whose bound shift broke to a check-in that is going out anyway, so the
    /// table shows the break at once. A break that would change the settle outcome is always
    /// recorded, in a transaction of its own.
    pub mark_broken: bool,
    /// Call `settle_stack` once `Board.round_id > end_round`.
    pub settle: bool,
    /// Compute limit of a settle (measured: see README).
    pub settle_cu_limit: u32,
    /// Seconds between settle attempts for one table.
    pub settle_retry_secs: u64,
    /// Settle attempts per table at most.
    pub settle_max_attempts: u32,
    /// Create the Bury vault and the lot's SKR token account when a settle or a forfeit needs
    /// them and nobody has (once: 3,114,040 lamports of rent at mainnet's rate, never returned).
    pub init_bury_vault: bool,
}

impl Default for StackConfig {
    fn default() -> Self {
        let cu = CheckinCu::default();
        StackConfig {
            enabled: true,
            discover_secs: 30,
            pass_interval_ms: 1_500,
            batch_wait_ms: 6_000,
            straggler_wait_ms: 3_000,
            late_slots: 40,
            retry_after_slots: 8,
            max_attempts_per_round: 4,
            max_seats_per_tx: crate::skr::MAX_SEATS,
            max_tables: 64,
            max_txs_per_pass: 16,
            simulate: true,
            cu_base: cu.base,
            cu_per_verify: cu.per_verify,
            cu_per_observe: cu.per_observe,
            cu_price_micro_lamports: 10_000,
            max_lamports_per_table: 100_000_000,
            max_lamports_per_hour: 10_000_000,
            mark_broken: true,
            settle: true,
            // Measured with the real program (fork suite): 5,413 CU for 3 seats with the Bury
            // transfer, 5,550 for 8 seats. The limit leaves room for a costlier token program.
            settle_cu_limit: 40_000,
            settle_retry_secs: 30,
            settle_max_attempts: 6,
            init_bury_vault: true,
        }
    }
}

impl StackConfig {
    /// Compute estimate for check-ins.
    pub fn cu(&self) -> CheckinCu {
        CheckinCu { base: self.cu_base, per_verify: self.cu_per_verify, per_observe: self.cu_per_observe }
    }

    /// When to send a table's batch.
    pub fn send_policy(&self) -> crate::stack::SendPolicy {
        crate::stack::SendPolicy {
            batch_wait: Duration::from_millis(self.batch_wait_ms),
            straggler_wait: Duration::from_millis(self.straggler_wait_ms),
            late_slots: self.late_slots,
        }
    }

    /// Retry knobs.
    pub fn retry(&self) -> crate::stack::CheckinRetry {
        crate::stack::CheckinRetry { retry_after_slots: self.retry_after_slots, max_attempts: self.max_attempts_per_round }
    }
}

/// Permissionless cleanups (INTERFACE v1.2 §11.6, §11.7). Each pays only what the program
/// fixes: a forfeited bond's SKR goes to the Bury lot and its rents to the owner, an expired
/// gift goes back to its sender. The crank gains nothing and pays the fee, so both are capped.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, default)]
pub struct CleanupConfig {
    /// Run the cleanup sweep at all.
    pub enabled: bool,
    /// How often to look for bonds and gifts.
    pub poll_secs: u64,
    /// `forfeit_focus_bond` for bonds whose shift sealed with a reason other than completed.
    pub forfeit_focus_bonds: bool,
    /// Also forfeit bonds whose shift can never be sealed (the rig was closed while the shift
    /// was open): there is no other way out for that SKR, and the rents return to the owner.
    pub forfeit_abandoned_bonds: bool,
    /// `refund_gift` for gifts past their `expiry_ts`.
    pub refund_gifts: bool,
    /// Seconds after `expiry_ts` before a gift is refunded (the cluster clock must be past it).
    pub gift_grace_secs: i64,
    /// Transactions per sweep at most.
    pub max_per_pass: usize,
    /// Fees per day at most (a rolling bucket), in lamports.
    pub max_lamports_per_day: u64,
    /// Compute limit of a forfeit (measured: see README).
    pub forfeit_cu_limit: u32,
    /// Compute limit of a refund.
    pub refund_cu_limit: u32,
    /// Leave a bond or a gift that failed alone for this long.
    pub retry_secs: u64,
}

impl Default for CleanupConfig {
    fn default() -> Self {
        CleanupConfig {
            enabled: true,
            poll_secs: 300,
            forfeit_focus_bonds: true,
            forfeit_abandoned_bonds: true,
            refund_gifts: true,
            gift_grace_secs: 60,
            max_per_pass: 4,
            max_lamports_per_day: 2_000_000,
            // Measured with the real program (fork suite): 6,551 to 7,919 CU for a forfeit,
            // 995 for a refund.
            forfeit_cu_limit: 30_000,
            refund_cu_limit: 5_000,
            retry_secs: 600,
        }
    }
}

/// Digging policy.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, default)]
pub struct DigConfig {
    /// Master switch (false = intake only).
    pub enabled: bool,
    /// Message format.
    pub tx_format: TxFormat,
    /// Start submitting when `end_slot - slot <= deploy_margin_slots` (deploy late).
    pub deploy_margin_slots: u64,
    /// Stop submitting when fewer slots than this remain.
    pub min_slots_left: u64,
    /// Re-plan and re-send (fresh blockhash) if not confirmed after this many slots.
    pub retry_after_slots: u64,
    /// Transactions per (rig, round) at most.
    pub max_attempts_per_round: u32,
    /// Rigs per transaction at most.
    pub max_rigs_per_tx: usize,
    /// Account-lock limit.
    pub max_account_locks: usize,
    /// Static priority fee (micro-lamports per CU).
    pub cu_price_micro_lamports: u64,
    /// Use `getRecentPrioritizationFees` instead of the static price.
    pub dynamic_priority_fee: bool,
    /// Percentile for the dynamic fee.
    pub priority_fee_percentile: u8,
    /// Cap for the dynamic fee.
    pub max_cu_price_micro_lamports: u64,
    /// Simulate first (size the CU limit, catch failures).
    pub simulate: bool,
    /// Headroom over simulated CU, percent.
    pub cu_margin_percent: u32,
    /// Estimate when not simulating.
    pub cu_estimate: CuEstimate,
    /// v1 loaded-accounts data size limit (bytes).
    pub loaded_accounts_data_size_limit: u32,
    /// Clock skew allowance for caps expiry and plan windows.
    pub clock_margin_secs: i64,
    /// Dig into rounds that have not started.
    pub start_rounds: bool,
    /// Extra lamports the Executor PDA must keep.
    pub executor_reserve_lamports: u64,
    /// Dig rigs whose Miner already deployed this round (no fee, so no reimbursement).
    pub dig_unpaid: bool,
    /// Checkpoint idle miners before their unsettled round expires.
    pub checkpoint_sweep: bool,
    /// Sweep miners whose unsettled round is this many rounds old.
    pub checkpoint_sweep_after_rounds: u64,
    /// Run the sweep every N rounds.
    pub checkpoint_sweep_interval_rounds: u64,
    /// Checkpoints per sweep transaction.
    pub checkpoints_per_tx: usize,
    /// ORE ProgramData upgrade slot pin (0 disables).
    pub ore_programdata_slot: u64,
    /// How often to re-read ORE's ProgramData header and the heads_down Config.
    pub config_poll_secs: u64,
    /// A dig pass reads nothing while the crank is idle: no heartbeat held, no known rig
    /// with a lease covering the round, nothing held or planned in the last few rounds (see
    /// [`crate::idle`]). An idle crank still makes one full pass every this many rounds, so
    /// that a rig whose heartbeat another crank applied is seen. A lease lasts at most 3
    /// rounds: only 3 or less can never miss one. 0 = every pass reads the chain.
    pub idle_full_read_rounds: u64,
}

impl Default for DigConfig {
    fn default() -> Self {
        DigConfig {
            enabled: true,
            tx_format: TxFormat::V0,
            deploy_margin_slots: 20,
            min_slots_left: 3,
            retry_after_slots: 6,
            max_attempts_per_round: 2,
            max_rigs_per_tx: 16,
            max_account_locks: crate::tx::DEFAULT_MAX_ACCOUNT_LOCKS,
            cu_price_micro_lamports: 1_000,
            dynamic_priority_fee: false,
            priority_fee_percentile: 75,
            max_cu_price_micro_lamports: 200_000,
            simulate: true,
            cu_margin_percent: 15,
            cu_estimate: CuEstimate::default(),
            loaded_accounts_data_size_limit: 4 * 1024 * 1024,
            clock_margin_secs: 5,
            start_rounds: false,
            executor_reserve_lamports: 0,
            dig_unpaid: false,
            checkpoint_sweep: true,
            checkpoint_sweep_after_rounds: 400,
            checkpoint_sweep_interval_rounds: 20,
            checkpoints_per_tx: 8,
            ore_programdata_slot: ore::PINNED_PROGRAMDATA_SLOT,
            config_poll_secs: 30,
            idle_full_read_rounds: 10,
        }
    }
}

impl DigConfig {
    /// Planner policy.
    pub fn policy(&self) -> Policy {
        Policy {
            clock_margin_secs: self.clock_margin_secs,
            start_rounds: self.start_rounds,
            executor_reserve: self.executor_reserve_lamports,
            dig_unpaid: self.dig_unpaid,
        }
    }
}

/// Intake limits (TOML form).
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, default)]
#[allow(missing_docs)]
pub struct IntakeToml {
    pub max_message_bytes: usize,
    pub max_connections: usize,
    pub max_connections_per_ip: u32,
    pub ip_burst: u32,
    pub ip_per_second: f64,
    pub rig_burst: u32,
    pub rig_per_second: f64,
    pub max_concurrent_verifications: usize,
    pub idle_timeout_secs: u64,
    pub unverified_timeout_secs: u64,
    pub trust_forwarded_for: bool,
    pub trust_real_ip: bool,
    pub max_heartbeat_rigs: usize,
    pub rig_cache_ttl_secs: u64,
    pub rig_fetches_per_second: f64,
}

impl Default for IntakeToml {
    fn default() -> Self {
        let d = IntakeConfig::default();
        IntakeToml {
            max_message_bytes: d.max_message_bytes,
            max_connections: d.max_connections,
            max_connections_per_ip: d.max_connections_per_ip,
            ip_burst: d.ip_quota.burst,
            ip_per_second: d.ip_quota.per_second,
            rig_burst: d.rig_quota.burst,
            rig_per_second: d.rig_quota.per_second,
            max_concurrent_verifications: d.max_concurrent_verifications,
            idle_timeout_secs: d.idle_timeout.as_secs(),
            unverified_timeout_secs: d.unverified_timeout.as_secs(),
            trust_forwarded_for: d.trust_forwarded_for,
            trust_real_ip: d.trust_real_ip,
            max_heartbeat_rigs: 100_000,
            rig_cache_ttl_secs: 60,
            rig_fetches_per_second: 50.0,
        }
    }
}

impl IntakeToml {
    /// Runtime form.
    pub fn to_runtime(&self) -> IntakeConfig {
        IntakeConfig {
            max_message_bytes: self.max_message_bytes,
            max_connections: self.max_connections,
            max_connections_per_ip: self.max_connections_per_ip,
            ip_quota: Quota::new(self.ip_burst, self.ip_per_second),
            rig_quota: Quota::new(self.rig_burst, self.rig_per_second),
            max_tracked_keys: IntakeConfig::default().max_tracked_keys,
            max_concurrent_verifications: self.max_concurrent_verifications,
            idle_timeout: Duration::from_secs(self.idle_timeout_secs),
            unverified_timeout: Duration::from_secs(self.unverified_timeout_secs),
            send_timeout: IntakeConfig::default().send_timeout,
            trust_forwarded_for: self.trust_forwarded_for,
            trust_real_ip: self.trust_real_ip,
            stale_chain_after: IntakeConfig::default().stale_chain_after,
            window_rule: IntakeConfig::default().window_rule,
        }
    }
}

impl Config {
    /// The intake's runtime limits: `[intake]` plus the streak-protection rule of `[signals]`.
    pub fn intake_runtime(&self) -> IntakeConfig {
        IntakeConfig { window_rule: self.signals.window_rule(), ..self.intake.to_runtime() }
    }
}

/// Lookup tables.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, default)]
pub struct AltConfig {
    /// Use lookup tables for v0 digs.
    pub enabled: bool,
    /// Tables to use (in addition to the ones in `state_dir`).
    pub tables: Vec<String>,
    /// Create a table when the crank owns none. A create is only sent when the fee payer
    /// holds the table's rent, its address is written to `state_dir` first, and a create that
    /// did not land is retried after a growing wait (1 min doubling to 1 h), not every round.
    pub auto_create: bool,
    /// Extend tables with shared and per-rig accounts as rigs appear.
    pub auto_extend: bool,
    /// Tables the crank will own at most, the first one included (256 addresses each; 0 =
    /// create none). A table the state file says this crank created counts whether or not
    /// the chain still shows it: after closing one by hand, remove it from the state file.
    /// The operator pays the rent and gets it back only by deactivating and
    /// closing the table by hand: on mainnet today (5,080 lamports per byte) 934,720 lamports
    /// for an empty table, 2,560,320 with the 10 shared accounts, 650,240 more per rig, and
    /// 42,550,080 for a full one.
    pub max_tables: usize,
}

impl Default for AltConfig {
    fn default() -> Self {
        AltConfig { enabled: true, tables: vec![], auto_create: true, auto_extend: true, max_tables: 8 }
    }
}

/// Submit path.
#[derive(Clone, Deserialize, Serialize, Default)]
#[serde(deny_unknown_fields, default)]
pub struct SenderConfig {
    /// Helius Sender endpoint (feature `helius-sender`), e.g.
    /// `https://sender.helius-rpc.com/fast`. Requires `tip_accounts` and `tip_lamports`.
    pub helius_sender_url: Option<String>,
    /// Tip recipients (from Helius' docs; one is picked per tx). No defaults on purpose.
    pub tip_accounts: Vec<String>,
    /// Tip per transaction.
    pub tip_lamports: u64,
}

impl std::fmt::Debug for SenderConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SenderConfig")
            .field("helius_sender_url", &self.helius_sender_url.as_deref().map(redact_url))
            .field("tip_accounts", &self.tip_accounts)
            .field("tip_lamports", &self.tip_lamports)
            .finish()
    }
}

/// Errors building the config.
#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    /// TOML problem.
    #[error("config file: {0}")]
    File(String),
    /// Invalid value.
    #[error("config: {0}")]
    Invalid(String),
    /// An environment override that does not parse. Names the variable, never its value.
    #[error("environment: {0}")]
    Env(String),
}

fn embeds_api_key(url: &str) -> bool {
    url.split(['?', '&'])
        .any(|kv| kv.to_ascii_lowercase().starts_with("api-key=") && !kv.contains(HELIUS_PLACEHOLDER))
}

/// The value of every `api-key=` query parameter in `url` (the secrets a log line must never
/// contain).
pub fn api_keys_in(url: &str) -> Vec<String> {
    url.split(['?', '&', '#'])
        .filter_map(|kv| {
            let (k, v) = kv.split_once('=')?;
            (k.eq_ignore_ascii_case("api-key") && !v.is_empty() && v != HELIUS_PLACEHOLDER).then(|| v.to_string())
        })
        .collect()
}

/// Replace `{HELIUS_API_KEY}` from the environment value `key`. The errors never contain the
/// key or the URL.
pub fn substitute_key(url: &str, key: Option<&str>) -> Result<String, ConfigError> {
    if !url.contains(HELIUS_PLACEHOLDER) {
        return Ok(url.to_string());
    }
    match key {
        Some("") => Err(ConfigError::Invalid("URL uses {HELIUS_API_KEY} but HELIUS_API_KEY is set to an empty value".into())),
        Some(k) if k.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_') => Ok(url.replace(HELIUS_PLACEHOLDER, k)),
        Some(_) => Err(ConfigError::Invalid("HELIUS_API_KEY has unexpected characters".into())),
        None => Err(ConfigError::Invalid("URL uses {HELIUS_API_KEY} but HELIUS_API_KEY is not set".into())),
    }
}

/// `https://…` → `wss://…`, `http://…` → `ws://…` (query string included: the Helius
/// WebSocket URL carries the same `api-key`). The result is a secret whenever the input is.
pub fn derive_ws_url(rpc: &str) -> String {
    if let Some(rest) = rpc.strip_prefix("https://") {
        format!("wss://{rest}")
    } else if let Some(rest) = rpc.strip_prefix("http://") {
        // Local validators serve WebSocket on RPC port + 1.
        match rest.split_once(':').and_then(|(h, p)| {
            let (port, tail) = p.split_once('/').map(|(a, b)| (a, format!("/{b}"))).unwrap_or((p, String::new()));
            port.parse::<u16>().ok().map(|n| format!("ws://{h}:{}{tail}", n.saturating_add(1)))
        }) {
            Some(u) => u,
            None => format!("ws://{rest}"),
        }
    } else {
        rpc.to_string()
    }
}

/// `HD_CRANK_` + the dotted path in upper case, dots as underscores.
fn env_name(path: &[String]) -> String {
    format!("{ENV_PREFIX}{}", path.join("_").to_ascii_uppercase())
}

fn walk_leaves(v: &Value, path: &mut Vec<String>, out: &mut Vec<(String, String, Value)>) {
    match v {
        Value::Object(m) => {
            for (k, child) in m {
                path.push(k.clone());
                walk_leaves(child, path, out);
                path.pop();
            }
        }
        leaf => out.push((env_name(path), path.join("."), leaf.clone())),
    }
}

/// Every setting: `(environment variable, dotted path, default as JSON)`, sorted by path.
pub fn env_settings() -> Vec<(String, String, Value)> {
    let tree = serde_json::to_value(Config::default()).unwrap_or(Value::Null);
    let mut out = Vec::new();
    walk_leaves(&tree, &mut Vec::new(), &mut out);
    out
}

/// `HD_CRANK_*` variable names among `names` that are neither a setting, an alias nor
/// reserved: almost always a typo, so the binary warns about them at start (names only).
pub fn unknown_env_overrides<'a>(names: impl IntoIterator<Item = &'a str>) -> Vec<String> {
    let known: std::collections::HashSet<String> = env_settings().into_iter().map(|(n, _, _)| n).collect();
    let mut out: Vec<String> = names
        .into_iter()
        .filter(|n| n.starts_with(ENV_PREFIX))
        .filter(|n| !known.contains(*n) && !RESERVED_ENV.contains(n) && !ENV_ALIASES.iter().any(|(a, _)| a == n))
        .map(str::to_string)
        .collect();
    out.sort();
    out
}

/// Parse an environment value into the JSON type of the setting's default.
fn parse_env_value(name: &str, raw: &str, like: &Value) -> Result<Value, ConfigError> {
    let bad = |what: &str| ConfigError::Env(format!("{name} is not {what}"));
    let t = raw.trim();
    Ok(match like {
        Value::Bool(_) => match t.to_ascii_lowercase().as_str() {
            "true" | "1" | "yes" | "on" => Value::Bool(true),
            "false" | "0" | "no" | "off" => Value::Bool(false),
            _ => return Err(bad("a boolean (true / false)")),
        },
        Value::Number(n) if n.is_f64() => {
            let f: f64 = t.parse().map_err(|_| bad("a number"))?;
            serde_json::Number::from_f64(f).map(Value::Number).ok_or_else(|| bad("a finite number"))?
        }
        Value::Number(_) => match t.parse::<u64>() {
            Ok(u) => Value::from(u),
            Err(_) => Value::from(t.parse::<i64>().map_err(|_| bad("an integer"))?),
        },
        Value::Array(_) => Value::Array(t.split(',').map(str::trim).filter(|s| !s.is_empty()).map(|s| Value::String(s.to_string())).collect()),
        Value::Null if t.is_empty() => Value::Null,
        Value::Null | Value::String(_) => Value::String(raw.to_string()),
        Value::Object(_) => return Err(bad("a single setting")),
    })
}

fn set_leaf(tree: &mut Value, path: &str, value: Value) {
    let mut cur = tree;
    let mut parts = path.split('.').peekable();
    while let Some(p) = parts.next() {
        if parts.peek().is_none() {
            if let Value::Object(m) = cur {
                m.insert(p.to_string(), value);
            }
            return;
        }
        match cur.get_mut(p) {
            Some(next) => cur = next,
            None => return,
        }
    }
}

impl Config {
    /// Parse TOML (refusing embedded API keys).
    pub fn from_toml(text: &str) -> Result<Self, ConfigError> {
        let c: Config = toml::from_str(text).map_err(|e| ConfigError::File(e.message().to_string()))?;
        for u in std::iter::once(&c.rpc_url).chain(c.ws_url.as_ref()).chain(c.sender.helius_sender_url.as_ref()) {
            if embeds_api_key(u) {
                return Err(ConfigError::Invalid(
                    "an API key is embedded in a URL in the config file; use {HELIUS_API_KEY} and the HELIUS_API_KEY env var".into(),
                ));
            }
        }
        Ok(c)
    }

    /// Apply the environment overrides (one variable per setting, see the module docs) and
    /// return the names that were applied.
    pub fn apply_env(self, env: &dyn Fn(&str) -> Option<String>) -> Result<(Self, Vec<String>), ConfigError> {
        let mut tree = serde_json::to_value(&self).map_err(|e| ConfigError::Invalid(e.to_string()))?;
        let mut applied = Vec::new();
        for (name, path, default) in env_settings() {
            // The alias first, then the canonical name (which wins when both are set).
            let alias = ENV_ALIASES.iter().find(|(_, canon)| *canon == name).map(|(a, _)| *a);
            for var in alias.into_iter().chain(std::iter::once(name.as_str())) {
                if let Some(raw) = env(var) {
                    set_leaf(&mut tree, &path, parse_env_value(var, &raw, &default)?);
                    applied.push(var.to_string());
                }
            }
        }
        let cfg: Config = serde_json::from_value(tree).map_err(|e| {
            // serde names the offending field; the message may quote a value, but only one that
            // already failed to be a URL-free scalar (an enum variant or a number out of range).
            ConfigError::Env(format!("an override has the wrong type or range: {e}"))
        })?;
        Ok((cfg, applied))
    }

    /// Apply environment overrides and key substitution, then validate.
    pub fn finalize(self, env: &dyn Fn(&str) -> Option<String>) -> Result<Self, ConfigError> {
        let (mut cfg, _) = self.apply_env(env)?;
        let key = env("HELIUS_API_KEY");
        cfg.rpc_url = substitute_key(&cfg.rpc_url, key.as_deref())?;
        let ws = cfg.ws_url.clone().unwrap_or_else(|| derive_ws_url(&cfg.rpc_url));
        cfg.ws_url = Some(substitute_key(&ws, key.as_deref())?);
        if let Some(s) = &cfg.sender.helius_sender_url {
            cfg.sender.helius_sender_url = Some(substitute_key(s, key.as_deref())?);
        }
        cfg.validate()?;
        Ok(cfg)
    }

    fn validate(&self) -> Result<(), ConfigError> {
        let bad = |m: &str| Err(ConfigError::Invalid(m.to_string()));
        if self.program_id.parse::<Address>().is_err() {
            return bad("program_id is not a base58 address");
        }
        if !matches!(self.commitment.as_str(), "processed" | "confirmed" | "finalized") {
            return bad("commitment must be processed, confirmed or finalized");
        }
        if self.chain_poll_secs == 0 || self.chain_poll_secs > MAX_CHAIN_POLL_SECS {
            return bad("chain_poll_secs must be 1..=20: above that, /healthz could report a stale slot between two polls that both succeeded");
        }
        let d = &self.dig;
        if d.min_slots_left >= d.deploy_margin_slots {
            return bad("dig.min_slots_left must be < dig.deploy_margin_slots");
        }
        if d.max_rigs_per_tx == 0 || d.max_rigs_per_tx > 255 {
            return bad("dig.max_rigs_per_tx must be 1..=255");
        }
        if d.cu_price_micro_lamports > d.max_cu_price_micro_lamports {
            return bad("dig.cu_price_micro_lamports exceeds dig.max_cu_price_micro_lamports");
        }
        if d.max_attempts_per_round == 0 {
            return bad("dig.max_attempts_per_round must be >= 1");
        }
        if d.priority_fee_percentile > 100 {
            return bad("dig.priority_fee_percentile must be <= 100");
        }
        if self.intake.max_message_bytes < 256 || self.intake.max_message_bytes > 64 * 1024 {
            return bad("intake.max_message_bytes must be 256..=65536");
        }
        if self.intake.trust_real_ip && self.intake.trust_forwarded_for {
            return bad("intake.trust_real_ip and intake.trust_forwarded_for name two different headers: set one");
        }
        if self.intake.idle_timeout_secs == 0 || self.intake.unverified_timeout_secs == 0 {
            return bad("intake.idle_timeout_secs and intake.unverified_timeout_secs must be >= 1");
        }
        if self.sender.helius_sender_url.is_some() && (self.sender.tip_accounts.is_empty() || self.sender.tip_lamports == 0) {
            return bad("sender.helius_sender_url needs sender.tip_accounts and sender.tip_lamports");
        }
        for t in &self.sender.tip_accounts {
            if t.parse::<Address>().is_err() {
                return bad("sender.tip_accounts has an invalid address");
            }
        }
        for t in &self.alt.tables {
            if t.parse::<Address>().is_err() {
                return bad("alt.tables has an invalid address");
            }
        }
        let s = &self.signals;
        if s.rig_burst == 0 || !(s.rig_per_minute.is_finite() && s.rig_per_minute >= 0.0) {
            return bad("signals.rig_burst must be >= 1 and signals.rig_per_minute a non-negative number");
        }
        if s.cu_limit == 0 || s.cu_limit > crate::tx::MAX_COMPUTE_UNITS || s.max_attempts == 0 || s.queue == 0 {
            return bad("signals.cu_limit must be 1..=1400000, signals.max_attempts and signals.queue >= 1");
        }
        if s.cu_price_micro_lamports > self.dig.max_cu_price_micro_lamports {
            return bad("signals.cu_price_micro_lamports exceeds dig.max_cu_price_micro_lamports");
        }
        if s.window_margin_secs < 0 {
            return bad("signals.window_margin_secs must be >= 0");
        }
        let r = &self.record;
        if r.every_rounds == 0 || r.max_rigs_per_tx == 0 || r.max_rigs_per_tx > hd::MAX_RIGS_PER_IX {
            return bad("record.every_rounds must be >= 1 and record.max_rigs_per_tx 1..=32");
        }
        let e = &self.end_shift;
        if e.max_per_pass == 0 || e.poll_secs == 0 || e.grace_secs < 0 || e.cu_limit == 0 {
            return bad("end_shift.max_per_pass and end_shift.poll_secs must be >= 1, end_shift.grace_secs >= 0");
        }
        let k = &self.stack;
        if k.max_seats_per_tx == 0 || k.max_seats_per_tx > crate::skr::MAX_SEATS {
            return bad("stack.max_seats_per_tx must be 1..=8");
        }
        if k.pass_interval_ms < 200 || k.discover_secs == 0 || k.max_tables == 0 || k.max_txs_per_pass == 0 {
            return bad("stack.pass_interval_ms must be >= 200; stack.discover_secs, stack.max_tables and stack.max_txs_per_pass >= 1");
        }
        if k.max_attempts_per_round == 0 || k.settle_max_attempts == 0 || k.settle_retry_secs == 0 {
            return bad("stack.max_attempts_per_round, stack.settle_max_attempts and stack.settle_retry_secs must be >= 1");
        }
        if k.settle_cu_limit == 0 || k.settle_cu_limit > crate::tx::MAX_COMPUTE_UNITS || k.cu_base == 0 {
            return bad("stack.settle_cu_limit must be 1..=1400000 and stack.cu_base >= 1");
        }
        if k.cu_price_micro_lamports > self.dig.max_cu_price_micro_lamports {
            return bad("stack.cu_price_micro_lamports exceeds dig.max_cu_price_micro_lamports");
        }
        let c = &self.cleanup;
        if c.poll_secs == 0 || c.max_per_pass == 0 || c.gift_grace_secs < 0 || c.forfeit_cu_limit == 0 || c.refund_cu_limit == 0 {
            return bad("cleanup.poll_secs and cleanup.max_per_pass must be >= 1, cleanup.gift_grace_secs >= 0, the CU limits >= 1");
        }
        Ok(())
    }

    /// Program id as an address (validated).
    pub fn program_id(&self) -> Address {
        self.program_id.parse().unwrap_or(hd::PROGRAM_ID)
    }

    /// The effective configuration without anything secret: every URL is cut down to scheme
    /// and host (keys live in the query, the path or the userinfo). This is what the binary
    /// logs at start and what `{:?}` prints.
    pub fn public_json(&self) -> Value {
        fn redact(v: &mut Value) {
            match v {
                Value::String(s) if s.contains("://") => *s = redact_url(s),
                Value::Array(a) => a.iter_mut().for_each(redact),
                Value::Object(m) => m.values_mut().for_each(redact),
                _ => {}
            }
        }
        let mut tree = serde_json::to_value(self).unwrap_or(Value::Null);
        redact(&mut tree);
        tree
    }

    /// Strings that must never appear in a log line: the API keys inside the configured URLs
    /// (after `{HELIUS_API_KEY}` was substituted).
    pub fn secrets(&self) -> Vec<String> {
        let mut out: Vec<String> = std::iter::once(&self.rpc_url)
            .chain(self.ws_url.as_ref())
            .chain(self.sender.helius_sender_url.as_ref())
            .flat_map(|u| api_keys_in(u))
            .collect();
        out.sort();
        out.dedup();
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn no_env(_: &str) -> Option<String> {
        None
    }

    #[test]
    fn defaults_are_valid() {
        let c = Config::from_toml("").unwrap().finalize(&no_env).unwrap();
        assert_eq!(c.ws_url.as_deref(), Some("wss://api.mainnet-beta.solana.com"));
        assert_eq!(c.program_id(), hd::PROGRAM_ID);
        assert!(c.secrets().is_empty());
    }

    #[test]
    fn helius_key_only_from_env() {
        let toml = r#"rpc_url = "https://mainnet.helius-rpc.com/?api-key={HELIUS_API_KEY}""#;
        let c = Config::from_toml(toml).unwrap();
        assert!(c.clone().finalize(&no_env).is_err(), "placeholder without env");
        let env = |k: &str| (k == "HELIUS_API_KEY").then(|| "abc-123".to_string());
        let c = c.finalize(&env).unwrap();
        assert_eq!(c.rpc_url, "https://mainnet.helius-rpc.com/?api-key=abc-123");
        assert_eq!(c.ws_url.as_deref(), Some("wss://mainnet.helius-rpc.com/?api-key=abc-123"));
        let embedded = r#"rpc_url = "https://mainnet.helius-rpc.com/?api-key=deadbeef""#;
        assert!(Config::from_toml(embedded).unwrap_err().to_string().contains("HELIUS_API_KEY"));
        let evil = |k: &str| (k == "HELIUS_API_KEY").then(|| "a&b=c".to_string());
        let err = Config::from_toml(toml).unwrap().finalize(&evil).unwrap_err().to_string();
        assert!(!err.contains("a&b"), "an error never echoes the key: {err}");
    }

    #[test]
    fn an_unset_an_empty_and_a_malformed_helius_key_each_get_their_own_message() {
        let toml = r#"rpc_url = "https://mainnet.helius-rpc.com/?api-key={HELIUS_API_KEY}""#;
        let message = |value: Option<&str>| {
            let value = value.map(str::to_string);
            let env = move |k: &str| (k == "HELIUS_API_KEY").then(|| value.clone()).flatten();
            Config::from_toml(toml).unwrap().finalize(&env).unwrap_err().to_string()
        };
        assert!(message(None).contains("HELIUS_API_KEY is not set"));
        // What a shared variable that does not resolve leaves behind: the name with no value.
        assert!(message(Some("")).contains("HELIUS_API_KEY is set to an empty value"), "{}", message(Some("")));
        assert!(!message(Some("")).contains("unexpected characters"));
        for bad in [" ", "abc def", "abc\n", "a/b"] {
            assert!(message(Some(bad)).contains("HELIUS_API_KEY has unexpected characters"), "{bad:?}");
        }
        // A URL without the placeholder needs no key, whatever the variable holds.
        let empty = |k: &str| (k == "HELIUS_API_KEY").then(String::new);
        assert!(Config::from_toml("").unwrap().finalize(&empty).is_ok());
    }

    #[test]
    fn the_chain_poll_interval_keeps_a_polled_slot_fresh() {
        // The bound is what /healthz and the RPC timeout leave: a poll that takes one whole
        // timeout to answer (both of its calls together) still moves the slot before it
        // counts as stale.
        assert_eq!(MAX_CHAIN_POLL_SECS, 20);
        assert_eq!(Duration::from_secs(MAX_CHAIN_POLL_SECS) + crate::rpc::RPC_TIMEOUT, IntakeConfig::default().stale_chain_after);
        assert_eq!(IntakeConfig::default().stale_chain_after, crate::intake::STALE_CHAIN_AFTER);
        let with = |secs: &str| Config::from_toml(&format!("chain_poll_secs = {secs}")).and_then(|c| c.finalize(&no_env));
        assert_eq!(Config::from_toml("").unwrap().finalize(&no_env).unwrap().chain_poll_secs, 5, "a config that does not name it keeps the 5 s poll");
        for ok in ["1", "5", "15", "20"] {
            assert_eq!(with(ok).unwrap().chain_poll_secs.to_string(), ok);
        }
        for bad in ["0", "21", "30", "600"] {
            let err = with(bad).unwrap_err().to_string();
            assert!(err.contains("chain_poll_secs must be 1..=20"), "{bad}: {err}");
        }
        let env = |k: &str| (k == "HD_CRANK_CHAIN_POLL_SECS").then(|| "15".to_string());
        assert_eq!(Config::from_toml("").unwrap().finalize(&env).unwrap().chain_poll_secs, 15);
        let env = |k: &str| (k == "HD_CRANK_CHAIN_POLL_SECS").then(|| "45".to_string());
        assert!(Config::from_toml("chain_poll_secs = 10").unwrap().finalize(&env).is_err(), "the environment is checked like the file");
    }

    #[test]
    fn the_idle_full_read_setting() {
        let c = Config::from_toml("").unwrap().finalize(&no_env).unwrap();
        assert_eq!(c.dig.idle_full_read_rounds, 10);
        let env = |k: &str| (k == "HD_CRANK_DIG_IDLE_FULL_READ_ROUNDS").then(|| "0".to_string());
        assert_eq!(Config::from_toml("[dig]\nidle_full_read_rounds = 3").unwrap().finalize(&env).unwrap().dig.idle_full_read_rounds, 0, "0 turns skipping off");
        assert!(Config::from_toml("[dig]\nidle_full_read_rounds = -1").is_err());
    }

    #[test]
    fn the_helius_websocket_url_is_built_from_the_key_and_never_printed() {
        let key = "5ecre7-k3y_ABCDEF0123456789";
        let env = |k: &str| (k == "HELIUS_API_KEY").then(|| key.to_string());
        // The WebSocket URL is derived from the RPC URL ...
        let derived = Config::from_toml(r#"rpc_url = "https://mainnet.helius-rpc.com/?api-key={HELIUS_API_KEY}""#).unwrap().finalize(&env).unwrap();
        // ... or given with its own placeholder.
        let explicit = Config::from_toml(
            "rpc_url = \"https://mainnet.helius-rpc.com/?api-key={HELIUS_API_KEY}\"\nws_url = \"wss://atlas-mainnet.helius-rpc.com/?api-key={HELIUS_API_KEY}\"",
        )
        .unwrap()
        .finalize(&env)
        .unwrap();
        for c in [&derived, &explicit] {
            let ws = c.ws_url.clone().unwrap();
            assert!(ws.starts_with("wss://") && ws.ends_with(&format!("api-key={key}")), "the builder puts the key in");
            // Nothing printable carries it: Debug, the public view, the startup log's fields.
            let debug = format!("{c:?}");
            assert!(!debug.contains(key), "{debug}");
            let public = c.public_json().to_string();
            assert!(!public.contains(key) && !public.contains("api-key"), "{public}");
            assert!(public.contains("helius-rpc.com"), "the host stays visible: {public}");
            assert_eq!(redact_url(&ws).matches(key).count(), 0);
            // The key is registered as a secret for the log scrubber.
            assert_eq!(c.secrets(), vec![key.to_string()]);
        }
        assert_eq!(derived.public_json()["ws_url"], "wss://mainnet.helius-rpc.com/<redacted>");
        assert_eq!(explicit.public_json()["ws_url"], "wss://atlas-mainnet.helius-rpc.com/<redacted>");
        // A key given through HD_CRANK_WS_URL (no placeholder) is a secret as well.
        let direct = |k: &str| (k == "HD_CRANK_WS_URL").then(|| format!("wss://example.org/?api-key={key}&x=1"));
        let c = Config::from_toml("").unwrap().finalize(&direct).unwrap();
        assert_eq!(c.secrets(), vec![key.to_string()]);
        assert!(!format!("{c:?}").contains(key));
        assert_eq!(api_keys_in("https://h/?a=1&API-KEY=k1#frag"), vec!["k1".to_string()]);
        assert!(api_keys_in("https://h/?api-key={HELIUS_API_KEY}").is_empty());
        // The sender's Debug is redacted too.
        let s = SenderConfig { helius_sender_url: Some(format!("https://sender.helius-rpc.com/fast?api-key={key}")), ..SenderConfig::default() };
        assert!(!format!("{s:?}").contains(key));
    }

    #[test]
    fn env_overrides_and_validation() {
        let env = |k: &str| match k {
            "HD_CRANK_RPC_URL" => Some("http://127.0.0.1:8899".to_string()),
            "HD_CRANK_TX_FORMAT" => Some("v1".to_string()),
            "HD_CRANK_KEYPAIR" => Some("/tmp/k.json".to_string()),
            _ => None,
        };
        let c = Config::from_toml("").unwrap().finalize(&env).unwrap();
        assert_eq!(c.ws_url.as_deref(), Some("ws://127.0.0.1:8900"));
        assert_eq!(c.dig.tx_format, TxFormat::V1);
        assert_eq!(c.keypair_path, Some(PathBuf::from("/tmp/k.json")));
        assert!(Config::from_toml("unknown_key = 1").is_err());
        assert!(Config::from_toml("[dig]\nmin_slots_left = 30").unwrap().finalize(&no_env).is_err());
        assert!(Config::from_toml("[dig]\ntx_format = \"v2\"").is_err());
        assert!(Config::from_toml("[sender]\nhelius_sender_url = \"https://sender.helius-rpc.com/fast\"")
            .unwrap()
            .finalize(&no_env)
            .is_err(), "sender without tip accounts");
        assert!(Config::from_toml("program_id = \"nope\"").unwrap().finalize(&no_env).is_err());
        let bad = |k: &str| (k == "HD_CRANK_TX_FORMAT").then(|| "v2".to_string());
        assert!(Config::from_toml("").unwrap().finalize(&bad).is_err());
    }

    #[test]
    fn every_setting_has_one_environment_variable() {
        let settings = env_settings();
        // Names are unique, upper case, and cover every section.
        let names: std::collections::HashSet<&str> = settings.iter().map(|(n, _, _)| n.as_str()).collect();
        assert_eq!(names.len(), settings.len(), "no two settings share a variable");
        assert!(settings.iter().all(|(n, _, _)| n.starts_with(ENV_PREFIX) && *n == n.to_ascii_uppercase()));
        for want in [
            "HD_CRANK_RPC_URL",
            "HD_CRANK_WS_URL",
            "HD_CRANK_KEYPAIR_PATH",
            "HD_CRANK_LISTEN",
            "HD_CRANK_LOG_JSON",
            "HD_CRANK_STATE_DIR",
            "HD_CRANK_SHUTDOWN_GRACE_SECS",
            "HD_CRANK_CHAIN_POLL_SECS",
            "HD_CRANK_DIG_IDLE_FULL_READ_ROUNDS",
            "HD_CRANK_DIG_TX_FORMAT",
            "HD_CRANK_DIG_CU_ESTIMATE_PER_RIG",
            "HD_CRANK_INTAKE_TRUST_FORWARDED_FOR",
            "HD_CRANK_ALT_TABLES",
            "HD_CRANK_SENDER_HELIUS_SENDER_URL",
            "HD_CRANK_SIGNALS_STREAK_PROTECTION",
            "HD_CRANK_RECORD_EVERY_ROUNDS",
            "HD_CRANK_END_SHIFT_MAX_LAMPORTS_PER_DAY",
            "HD_CRANK_STACK_MAX_LAMPORTS_PER_TABLE",
            "HD_CRANK_STACK_CU_PRICE_MICRO_LAMPORTS",
            "HD_CRANK_CLEANUP_REFUND_GIFTS",
        ] {
            assert!(names.contains(want), "{want}");
        }
        // The aliases point at real settings, and the reserved names are not settings.
        for (alias, canon) in ENV_ALIASES {
            assert!(names.contains(canon) && !names.contains(alias), "{alias} -> {canon}");
        }
        assert!(RESERVED_ENV.iter().all(|r| !names.contains(r)));

        // Overriding EVERY setting through its variable changes exactly that setting: the
        // override of each leaf is a value of its own type that differs from the default.
        let other = |default: &Value, path: &str| -> String {
            match default {
                Value::Bool(b) => (!b).to_string(),
                Value::Number(n) if n.is_f64() => format!("{}", n.as_f64().unwrap() + 0.5),
                Value::Number(n) => match path {
                    "dig.priority_fee_percentile" => "50".into(),
                    _ => (n.as_u64().unwrap_or(0) + 1).to_string(),
                },
                Value::Array(_) => "11111111111111111111111111111111,So11111111111111111111111111111111111111112".into(),
                Value::Null if path.ends_with("url") => "https://example.org/x".into(),
                Value::Null => "/tmp/kp.json".into(),
                Value::String(_) => match path {
                    "commitment" => "finalized".into(),
                    "program_id" => "11111111111111111111111111111111".into(),
                    "dig.tx_format" => "legacy".into(),
                    "rpc_url" => "https://rpc.example.org".into(),
                    "listen" => "127.0.0.1:1".into(),
                    _ => "other".into(),
                },
                Value::Object(_) => unreachable!(),
            }
        };
        let base = serde_json::to_value(Config::default()).unwrap();
        for (name, path, default) in &settings {
            let value = other(default, path);
            let env = |k: &str| (k == name).then(|| value.clone());
            let (cfg, applied) = Config::default().apply_env(&env).unwrap_or_else(|e| panic!("{name}: {e}"));
            assert_eq!(applied, vec![name.clone()]);
            let tree = serde_json::to_value(&cfg).unwrap();
            // Exactly one leaf differs from the defaults: this one.
            let (mut a, mut b) = (Vec::new(), Vec::new());
            walk_leaves(&base, &mut Vec::new(), &mut a);
            walk_leaves(&tree, &mut Vec::new(), &mut b);
            let changed: Vec<&String> = a.iter().zip(&b).filter(|(x, y)| x.2 != y.2).map(|(x, _)| &x.1).collect();
            assert_eq!(changed, vec![path], "{name} changes only {path}");
        }
        assert!(settings.len() > 110, "{} settings", settings.len());
    }

    #[test]
    fn env_values_parse_by_type_and_errors_never_echo_them() {
        let env = |k: &str| match k {
            "HD_CRANK_LOG_JSON" => Some("YES".to_string()),
            "HD_CRANK_STACK_ENABLED" => Some("off".to_string()),
            "HD_CRANK_DIG_CLOCK_MARGIN_SECS" => Some(" 9 ".to_string()),
            "HD_CRANK_INTAKE_IP_PER_SECOND" => Some("4".to_string()),
            "HD_CRANK_ALT_TABLES" => Some("11111111111111111111111111111111, So11111111111111111111111111111111111111112".to_string()),
            "HD_CRANK_STACK_MAX_LAMPORTS_PER_TABLE" => Some("0".to_string()),
            "HD_CRANK_KEYPAIR" => Some("/a.json".to_string()),
            "HD_CRANK_KEYPAIR_PATH" => Some("/b.json".to_string()),
            _ => None,
        };
        let c = Config::from_toml("log_json = false\n[stack]\nenabled = true").unwrap().finalize(&env).unwrap();
        assert!(c.log_json && !c.stack.enabled, "the environment wins over the file");
        assert_eq!(c.dig.clock_margin_secs, 9);
        assert_eq!(c.intake.ip_per_second, 4.0);
        assert_eq!(c.alt.tables.len(), 2);
        assert_eq!(c.stack.max_lamports_per_table, 0);
        assert_eq!(c.keypair_path, Some(PathBuf::from("/b.json")), "the canonical name wins over its alias");
        // An optional setting is cleared by an empty value.
        let clear = |k: &str| (k == "HD_CRANK_KEYPAIR_PATH").then(String::new);
        let c = Config::from_toml("keypair_path = \"/x.json\"").unwrap().finalize(&clear).unwrap();
        assert_eq!(c.keypair_path, None);
        // Bad values: the error names the variable and never the value.
        for (name, value) in [
            ("HD_CRANK_LOG_JSON", "s3cr3t-maybe"),
            ("HD_CRANK_DIG_MAX_RIGS_PER_TX", "s3cr3t-twelve"),
            ("HD_CRANK_INTAKE_IP_PER_SECOND", "s3cr3t-fast"),
            ("HD_CRANK_DIG_CLOCK_MARGIN_SECS", "s3cr3t-1.5"),
        ] {
            let env = |k: &str| (k == name).then(|| value.to_string());
            let err = Config::from_toml("").unwrap().finalize(&env).unwrap_err().to_string();
            assert!(err.contains(name), "{err}");
            assert!(!err.contains("s3cr3t"), "{err}");
        }
        // Out of range for the field's type, and invalid after validation.
        let env = |k: &str| (k == "HD_CRANK_DIG_PRIORITY_FEE_PERCENTILE").then(|| "300".to_string());
        assert!(Config::from_toml("").unwrap().finalize(&env).is_err());
        let env = |k: &str| (k == "HD_CRANK_STACK_MAX_SEATS_PER_TX").then(|| "9".to_string());
        assert!(Config::from_toml("").unwrap().finalize(&env).unwrap_err().to_string().contains("stack.max_seats_per_tx"));
        // Negative integers parse where the field is signed, and validation still applies.
        let env = |k: &str| (k == "HD_CRANK_END_SHIFT_GRACE_SECS").then(|| "-1".to_string());
        assert!(Config::from_toml("").unwrap().finalize(&env).unwrap_err().to_string().contains("grace_secs"));
        // Typos are reported by name; settings, aliases and reserved names are not.
        let unknown = unknown_env_overrides(["HD_CRANK_DIG_ENABELD", "HD_CRANK_DIG_ENABLED", "HD_CRANK_KEYPAIR", "HD_CRANK_CONFIG", "HD_CRANK_KEYPAIR_JSON", "PATH", "HELIUS_API_KEY"]);
        assert_eq!(unknown, vec!["HD_CRANK_DIG_ENABELD".to_string()]);
    }

    #[test]
    fn signal_record_and_end_shift_sections() {
        let c = Config::from_toml("").unwrap().finalize(&no_env).unwrap();
        assert!(c.signals.enabled && c.record.enabled && c.end_shift.enabled);
        assert_eq!(c.signals.est_fee(), 10_000 + 100, "2 signatures + 5k CU x 20,000 micro-lamports");
        let t = "[signals]\nenabled = false\nmax_lamports_per_hour = 5\n[record]\nevery_rounds = 1\ngate_closed_rigs = true\n[end_shift]\nmax_lamports_per_day = 0\n";
        let c = Config::from_toml(t).unwrap().finalize(&no_env).unwrap();
        assert!(!c.signals.hub().enabled);
        assert_eq!(c.record.policy(5).every_rounds, 1);
        assert_eq!(c.end_shift.max_lamports_per_day, 0);
        assert!(Config::from_toml("[record]\nevery_rounds = 0").unwrap().finalize(&no_env).is_err());
        assert!(Config::from_toml("[record]\nmax_rigs_per_tx = 33").unwrap().finalize(&no_env).is_err());
        assert!(Config::from_toml("[signals]\ncu_limit = 0").unwrap().finalize(&no_env).is_err());
        assert!(Config::from_toml("[end_shift]\ngrace_secs = -1").unwrap().finalize(&no_env).is_err());
        assert!(Config::from_toml("[signals]\nsurprise = 1").is_err());
        assert!(Config::from_toml("[signals]\nwindow_margin_secs = -1").unwrap().finalize(&no_env).is_err());
    }

    #[test]
    fn stack_and_cleanup_sections() {
        let c = Config::from_toml("").unwrap().finalize(&no_env).unwrap();
        assert!(c.stack.enabled && c.stack.settle && c.cleanup.enabled && c.signals.streak_protection);
        assert_eq!(c.stack.cu(), CheckinCu::default());
        assert_eq!(c.stack.retry().max_attempts, 4);
        assert_eq!(c.stack.send_policy().late_slots, 40);
        let t = "[stack]\nenabled = false\nmax_lamports_per_table = 123\nmark_broken = false\n[cleanup]\nrefund_gifts = false\nmax_per_pass = 1\n";
        let c = Config::from_toml(t).unwrap().finalize(&no_env).unwrap();
        assert!(!c.stack.enabled && !c.stack.mark_broken && !c.cleanup.refund_gifts);
        assert_eq!((c.stack.max_lamports_per_table, c.cleanup.max_per_pass), (123, 1));
        for bad in [
            "[stack]\nmax_seats_per_tx = 0",
            "[stack]\nmax_seats_per_tx = 9",
            "[stack]\npass_interval_ms = 10",
            "[stack]\nmax_attempts_per_round = 0",
            "[stack]\ncu_price_micro_lamports = 999999999",
            "[stack]\nsurprise = 1",
            "[cleanup]\npoll_secs = 0",
            "[cleanup]\ngift_grace_secs = -5",
        ] {
            assert!(Config::from_toml(bad).and_then(|c| c.finalize(&no_env)).is_err(), "{bad}");
        }
    }

    #[test]
    fn the_example_config_is_valid_and_matches_the_defaults() {
        let env = |k: &str| (k == "HELIUS_API_KEY").then(|| "abc".to_string());
        let c = Config::from_toml(include_str!("../crank.example.toml")).unwrap().finalize(&env).unwrap();
        // Every setting the example spells out is the default (except the Helius URLs).
        let mut got = Vec::new();
        let mut want = Vec::new();
        walk_leaves(&serde_json::to_value(&c).unwrap(), &mut Vec::new(), &mut got);
        walk_leaves(&serde_json::to_value(Config::default()).unwrap(), &mut Vec::new(), &mut want);
        for (g, w) in got.iter().zip(&want) {
            if matches!(g.1.as_str(), "rpc_url" | "ws_url") {
                continue;
            }
            assert_eq!(g.2, w.2, "{} in crank.example.toml differs from the default", g.1);
        }
    }

    #[test]
    fn the_railway_config_parses_and_stays_overridable() {
        // deploy/railway/crank/crank.toml is baked into the image; the entrypoint passes
        // --config, --keypair, HD_CRANK_LISTEN (from PORT), HELIUS_API_KEY and RUST_LOG.
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../deploy/railway/crank/crank.toml");
        let Ok(text) = std::fs::read_to_string(path) else {
            return; // the crank can be built without the deploy directory
        };
        let env = |k: &str| match k {
            "HELIUS_API_KEY" => Some("railway-key-0123456789".to_string()),
            "HD_CRANK_LISTEN" => Some("[::]:8787".to_string()),
            "HD_CRANK_STACK_MAX_LAMPORTS_PER_HOUR" => Some("5000000".to_string()),
            _ => None,
        };
        let c = Config::from_toml(&text).unwrap().finalize(&env).unwrap();
        assert_eq!(c.listen, "[::]:8787");
        // Railway documents X-Real-IP as the client's address; X-Forwarded-For is not trusted there.
        assert!(c.log_json && c.intake.trust_real_ip && !c.intake.trust_forwarded_for);
        assert_eq!(c.stack.max_lamports_per_hour, 5_000_000);
        assert!(c.stack.enabled && c.cleanup.enabled, "the new duties are on by default on Railway");
        assert!(!format!("{c:?}").contains("railway-key"));
        // The file's own values (without the override above): the watcher's HTTP poll is
        // slower than the default and inside the range /healthz allows, and no spend cap of
        // the three it sizes lets a duty use more than about a quarter of a 0.05 SOL fee payer
        // in a day (a bucket starts full and refills once per period).
        let env = |k: &str| (k == "HELIUS_API_KEY").then(|| "railway-key-0123456789".to_string());
        let c = Config::from_toml(&text).unwrap().finalize(&env).unwrap();
        assert_eq!(c.chain_poll_secs, 15);
        assert!(c.chain_poll_secs > Config::default().chain_poll_secs && c.chain_poll_secs <= MAX_CHAIN_POLL_SECS);
        assert_eq!(c.dig.idle_full_read_rounds, 10);
        let float = 50_000_000u64;
        let per_day = [
            ("signals", c.signals.max_lamports_per_hour * 25),
            ("end_shift", c.end_shift.max_lamports_per_day * 2),
            ("stack", c.stack.max_lamports_per_hour * 25),
        ];
        for (duty, lamports) in per_day {
            assert!(lamports <= float / 4, "{duty}: up to {lamports} lamports in a day");
        }
        assert_eq!(c.end_shift.max_lamports_per_day, 6_000_000);
        assert!(c.stack.max_lamports_per_table <= float / 4);
        assert_eq!(c.alt.max_tables, 1);
        assert!(c.alt.enabled && c.alt.auto_create && c.end_shift.enabled && c.record.gate_closed_rigs);
    }

    #[test]
    fn the_railway_env_example_names_real_settings_with_values_that_load() {
        // deploy/railway/crank/.env.example lists overrides as comments (`# HD_CRANK_X=value`).
        // Each one must be a setting this binary knows, with a value it accepts: a misspelled
        // name is only a warning at start, and the override silently does nothing.
        let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/../deploy/railway/crank/");
        let (Ok(example), Ok(toml)) = (std::fs::read_to_string(format!("{dir}.env.example")), std::fs::read_to_string(format!("{dir}crank.toml")))
        else {
            return; // the crank can be built without the deploy directory
        };
        let overrides: Vec<(String, String)> = example
            .lines()
            .filter_map(|l| l.strip_prefix("# HD_CRANK_"))
            .filter_map(|l| l.split_once('='))
            .map(|(name, value)| (format!("HD_CRANK_{name}"), value.to_string()))
            .filter(|(name, value)| name.bytes().all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_') && !value.contains('<'))
            .collect();
        let names: Vec<&str> = overrides.iter().map(|(n, _)| n.as_str()).collect();
        let profile = [
            ("HD_CRANK_ALT_ENABLED", "false"),
            ("HD_CRANK_STACK_ENABLED", "false"),
            ("HD_CRANK_STACK_INIT_BURY_VAULT", "false"),
            ("HD_CRANK_END_SHIFT_ENABLED", "false"),
            ("HD_CRANK_CLEANUP_ENABLED", "false"),
            ("HD_CRANK_INTAKE_RIG_FETCHES_PER_SECOND", "1"),
        ];
        for (name, value) in profile {
            assert!(overrides.contains(&(name.to_string(), value.to_string())), "the one-phone profile has {name}={value}");
        }
        assert!(names.len() >= 12, "{names:?}");
        assert_eq!(unknown_env_overrides(names.iter().copied()), Vec::<String>::new());
        // All of them at once load on top of the Railway file and say what they say.
        let env = |k: &str| match k {
            "HELIUS_API_KEY" => Some("railway-key-0123456789".to_string()),
            _ => overrides.iter().find(|(n, _)| n == k).map(|(_, v)| v.clone()),
        };
        let c = Config::from_toml(&toml).unwrap().finalize(&env).unwrap();
        assert!(!c.alt.enabled && !c.stack.enabled && !c.stack.init_bury_vault && !c.end_shift.enabled && !c.cleanup.enabled);
        assert_eq!(c.intake.rig_fetches_per_second, 1.0);
        assert!(c.dig.enabled && c.signals.enabled && c.record.enabled, "the profile leaves digging, BREAK / FREEZE and records on");
    }
}
