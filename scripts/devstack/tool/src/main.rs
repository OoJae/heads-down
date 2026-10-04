//! `hd-devstack`: the Rust half of `scripts/devstack` (see `docs/DEVSTACK.md`) and of
//! `scripts/mainnet` (see `docs/DEPLOY.md`).
//!
//! | Subcommand | What | Clusters |
//! |---|---|---|
//! | `genesis` | mainnet dump → `--account` files for `solana-test-validator` (minimal surgery) | local files |
//! | `surgery` | the same rewrites applied live on Surfpool (`surfnet_setAccount`) | localnet |
//! | `init` | heads_down `initialize_config` + Executor PDA float (idempotent) | any |
//! | `preflight` | read-only go/no-go before a deploy | any |
//! | `funding` | exact funding per key for the first deploy, from the cluster's rent | any |
//! | `write-buffer` | the deploy's buffer, created and written at a set rate; resumes (`buffer.rs`) | any |
//! | `buffer-status` | what that buffer holds and how many chunks are still to write | any |
//! | `verify-deploy` | deployed bytes == local `.so`, then the public deploy receipt | any |
//! | `fees` | the transactions an address paid for after a slot, and their fees | any |
//! | `propose-config` / `apply-config` | governance (`--paused 1` pauses `dig` at once) | any |
//! | `status` | ORE / heads_down state | any |
//! | `driver` | the ORE round driver (background miner, entropy reveal, `reset`) | localnet |
//! | `smoke` | the phone-less end-to-end "trustless beat" | localnet |
//! | `clock-in` | a Mac-held dev wallet arms a rig for a phone's Keystore P-256 key | localnet |
//! | `fund` | airdrop SOL on the local validator | localnet |
//! | `probe` | engine capability check (secp256r1 precompile really verifies, v1 getTransaction, SPL Token) | localnet |
//!
//! `--cluster` (env `HD_CLUSTER`, default `localnet`) is checked against the RPC's genesis hash
//! and host before anything is read or signed (`cluster.rs`). The RPC URL may carry a provider
//! key: pass it in `HD_DEVSTACK_RPC` (never on the command line); only its host is printed.

#![forbid(unsafe_code)]

mod admin;
mod buffer;
mod clockin;
mod cluster;
mod driver;
mod entropy;
mod genesis;
mod hd;
mod ops;
mod ore;
mod phone;
mod smoke;
mod util;

use std::path::PathBuf;
use std::time::Duration;

use anyhow::Result;
use clap::{Parser, Subcommand};
use cluster::{require_local, Cluster};
use ops::{DeployMode, KeyNeed};
use solana_address::Address;

#[derive(Parser)]
#[command(name = "hd-devstack", about = "Heads Down local dev stack and deploy operations")]
struct Cli {
    /// Target cluster; checked against the RPC's genesis hash and host.
    #[arg(long, global = true, env = "HD_CLUSTER", value_enum, default_value_t = Cluster::Localnet)]
    cluster: Cluster,
    /// JSON-RPC URL (default: the cluster's public endpoint, or 127.0.0.1:8899 for localnet).
    /// Keyed provider URLs belong in HD_DEVSTACK_RPC, not on the command line.
    #[arg(long, global = true, env = "HD_DEVSTACK_RPC", hide_env_values = true)]
    rpc: Option<String>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Rewrite a mainnet dump into validator genesis accounts.
    Genesis {
        #[arg(long)]
        fixtures: PathBuf,
        #[arg(long)]
        out: PathBuf,
        #[arg(long)]
        entropy_secret: PathBuf,
        /// Override ORE round_slots (default: mainnet's).
        #[arg(long)]
        round_slots: Option<u64>,
        /// Override ORE intermission_slots (default: mainnet's).
        #[arg(long)]
        intermission_slots: Option<u64>,
    },
    /// Surfpool: apply the fork surgery live through surfnet_setAccount.
    Surgery {
        #[arg(long)]
        entropy_secret: PathBuf,
        #[arg(long)]
        round_slots: Option<u64>,
        #[arg(long)]
        intermission_slots: Option<u64>,
    },
    /// initialize_config (signed by the upgrade authority) + fund the Executor PDA float.
    /// Idempotent: an existing Config is left alone and the float is topped up to its target.
    Init {
        /// Upgrade authority keypair (signs and pays).
        #[arg(long)]
        authority: PathBuf,
        #[arg(long)]
        governance: Address,
        #[arg(long)]
        registrar: Address,
        /// Config.executor_fee in lamports (immutable once written).
        #[arg(long, default_value_t = 10_000)]
        executor_fee: u64,
        /// Config.crank_fee in lamports (<= executor_fee).
        #[arg(long, default_value_t = 7_000)]
        crank_fee: u64,
        /// Config.bury_bps (<= 10000; no bury path yet).
        #[arg(long, default_value_t = 0)]
        bury_bps: u16,
        /// Executor PDA float target in lamports (default: rent-exempt(0) + 10 x CHECKPOINT_FEE
        /// + crank-reserve-digs x crank_fee).
        #[arg(long)]
        executor_float: Option<u64>,
        /// Crank reimbursements the default float covers.
        #[arg(long, default_value_t = 100)]
        crank_reserve_digs: u64,
        /// Priority fee in micro-lamports per compute unit.
        #[arg(long, default_value_t = 0)]
        cu_price: u64,
        /// Mainnet: send (the default only prints the plan).
        #[arg(long)]
        yes: bool,
        /// Write a JSON receipt (public values only) here.
        #[arg(long)]
        receipt: Option<PathBuf>,
    },
    /// Read-only go/no-go before a heads_down deploy (exit 1 on NO-GO).
    Preflight {
        /// The built program (.so).
        #[arg(long)]
        so: PathBuf,
        /// --max-len the deploy will use.
        #[arg(long)]
        max_len: u64,
        /// Pubkey of the program keypair.
        #[arg(long)]
        program_id: Address,
        /// Deployer pubkey (fee payer and upgrade authority).
        #[arg(long)]
        deployer: Address,
        #[arg(long, value_enum, default_value_t = DeployMode::Fresh)]
        mode: DeployMode,
        #[arg(long, default_value_t = 10_000)]
        executor_fee: u64,
        #[arg(long, default_value_t = 7_000)]
        crank_fee: u64,
        #[arg(long)]
        executor_float: Option<u64>,
        #[arg(long, default_value_t = 100)]
        crank_reserve_digs: u64,
        /// Lamports budgeted for the deploy's transaction fees.
        #[arg(long, default_value_t = 5_000_000)]
        fee_budget: u64,
        /// Lamports one transaction is budgeted at: with it, a deploy that continues an
        /// existing buffer is budgeted for the chunks still to write only.
        #[arg(long)]
        fee_per_tx: Option<u64>,
        /// The deploy's per-commit buffer address: if the account exists it is checked, and
        /// what it already holds is counted.
        #[arg(long)]
        buffer: Option<Address>,
        /// Other keys to report: label=PUBKEY[:MIN_LAMPORTS] (repeatable).
        #[arg(long = "key")]
        keys: Vec<KeyNeed>,
        /// Accept a scheduled SIMD-0500 for an SBPF v0-v2 build.
        #[arg(long)]
        allow_simd0500_pending: bool,
        /// Recent ORE transactions to scan (at most) for Automation / Miner layouts.
        #[arg(long, default_value_t = 40)]
        sample_txs: usize,
        /// Write every measured value here (JSON).
        #[arg(long)]
        json: Option<PathBuf>,
    },
    /// Exact funding per key for the first deploy, from the cluster's rent (read-only).
    Funding {
        /// Deployer pubkey.
        #[arg(long)]
        deployer: Address,
        /// Program size in bytes (the .so length).
        #[arg(long)]
        so_len: u64,
        #[arg(long)]
        max_len: u64,
        #[arg(long, default_value_t = 7_000)]
        crank_fee: u64,
        #[arg(long)]
        executor_float: Option<u64>,
        #[arg(long, default_value_t = 100)]
        crank_reserve_digs: u64,
        #[arg(long, default_value_t = 5_000_000)]
        fee_budget: u64,
        /// Other keys: label=PUBKEY[:RECOMMENDED_LAMPORTS] (repeatable).
        #[arg(long = "key")]
        keys: Vec<KeyNeed>,
        #[arg(long)]
        json: Option<PathBuf>,
    },
    /// Create the deploy's buffer if it is missing and write what differs from the build, at
    /// a rate a rate-limited RPC accepts. Safe to kill and to run again: it resumes.
    WriteBuffer {
        /// The built program (.so).
        #[arg(long)]
        so: PathBuf,
        /// Buffer keypair (it signs the buffer's creation only).
        #[arg(long)]
        buffer: PathBuf,
        /// Deployer keypair: fee payer and buffer authority.
        #[arg(long)]
        authority: PathBuf,
        /// What the buffer is for: it decides the lamports a new buffer is created with.
        #[arg(long, value_enum, default_value_t = DeployMode::Fresh)]
        mode: DeployMode,
        /// --max-len of a fresh deploy (a new buffer then holds the ProgramData rent for it).
        #[arg(long)]
        max_len: u64,
        /// Write transactions a second (Helius' free plan allows one sendTransaction a second).
        #[arg(long, default_value_t = 1.0)]
        rate: f64,
        /// Priority fee in micro-lamports per compute unit.
        #[arg(long, default_value_t = 0)]
        cu_price: u64,
        /// Mainnet: send (the default only prints the plan).
        #[arg(long)]
        yes: bool,
        /// Write what was done here (JSON, public values only).
        #[arg(long)]
        json: Option<PathBuf>,
    },
    /// What a deploy's buffer holds and how many chunks are still to write (read-only).
    BufferStatus {
        /// The built program (.so).
        #[arg(long)]
        so: PathBuf,
        /// Buffer address.
        #[arg(long)]
        buffer: Address,
        /// Deployer pubkey (fee payer and buffer authority).
        #[arg(long)]
        deployer: Address,
        #[arg(long)]
        json: Option<PathBuf>,
    },
    /// The transactions an address paid for after a slot, and their fees (read-only).
    Fees {
        /// The fee payer.
        #[arg(long)]
        payer: Address,
        /// Count transactions in slots after this one.
        #[arg(long, default_value_t = 0)]
        since_slot: u64,
        #[arg(long)]
        json: Option<PathBuf>,
    },
    /// Check the deployed (or buffered) bytes against the local .so and write the receipt.
    VerifyDeploy {
        #[arg(long, value_enum, default_value_t = DeployMode::Fresh)]
        mode: DeployMode,
        #[arg(long)]
        so: PathBuf,
        #[arg(long)]
        program_id: Address,
        /// Fee payer.
        #[arg(long)]
        deployer: Address,
        /// Expected upgrade / buffer authority (default: the deployer).
        #[arg(long)]
        authority: Option<Address>,
        /// Buffer address (buffer mode).
        #[arg(long)]
        buffer: Option<Address>,
        /// Deploy transaction signature.
        #[arg(long)]
        signature: Option<String>,
        #[arg(long)]
        max_len: Option<u64>,
        /// Fee-payer balance before the deploy (lamports).
        #[arg(long)]
        balance_before: Option<u64>,
        /// The `--json` file of `write-buffer`, copied into the receipt.
        #[arg(long)]
        buffer_write: Option<PathBuf>,
        /// Slot at which the Solana CLI was started: the fee payer's transactions after it
        /// are counted into the receipt.
        #[arg(long)]
        cli_since_slot: Option<u64>,
        /// Build facts for the receipt: key=value (repeatable).
        #[arg(long = "meta", value_parser = parse_kv)]
        meta: Vec<(String, String)>,
        /// Receipt path.
        #[arg(long)]
        out: PathBuf,
    },
    /// propose_config: fields default to their current values; --paused 1 pauses dig at once.
    ProposeConfig {
        /// Config.governance keypair.
        #[arg(long)]
        governance: PathBuf,
        #[arg(long)]
        registrar: Option<Address>,
        #[arg(long)]
        crank_fee: Option<u64>,
        #[arg(long)]
        bury_bps: Option<u16>,
        #[arg(long)]
        paused: Option<u8>,
        #[arg(long, default_value_t = 0)]
        cu_price: u64,
        #[arg(long)]
        yes: bool,
    },
    /// apply_config after the timelock (any payer).
    ApplyConfig {
        #[arg(long)]
        payer: PathBuf,
        #[arg(long, default_value_t = 0)]
        cu_price: u64,
        #[arg(long)]
        yes: bool,
    },
    /// Keep ORE rounds advancing.
    Driver {
        #[arg(long)]
        payer: PathBuf,
        #[arg(long)]
        entropy_secret: PathBuf,
        /// Background miner keypair (omit to never start rounds).
        #[arg(long)]
        background: Option<PathBuf>,
        #[arg(long, default_value_t = 10_000)]
        background_lamports: u64,
        #[arg(long, default_value_t = 2)]
        start_delay_slots: u64,
        /// Stop after N resets.
        #[arg(long)]
        rounds: Option<u64>,
    },
    /// Phone-less end-to-end smoke.
    Smoke {
        #[arg(long, default_value = "ws://127.0.0.1:8787/ws")]
        crank_ws: String,
        #[arg(long, default_value = "http://127.0.0.1:8787")]
        crank_http: String,
        #[arg(long, default_value = "http://127.0.0.1:8788")]
        indexer: String,
        /// Per-phase timeout in seconds.
        #[arg(long, default_value_t = 480)]
        timeout_secs: u64,
    },
    /// Arm a rig for an external phone's P-256 key (hex, 33-byte SEC1 compressed).
    ClockIn {
        /// Dev wallet keypair (created by clock-in.sh under ~/.config/heads-down/devstack).
        #[arg(long)]
        wallet: PathBuf,
        #[arg(long)]
        p256: String,
        #[arg(long, default_value_t = 2)]
        lease: u8,
        #[arg(long, default_value_t = 8.0)]
        hours: f64,
    },
    /// Airdrop SOL to a pubkey.
    Fund {
        to: Address,
        #[arg(default_value_t = 10.0)]
        sol: f64,
    },
    /// Print ORE and heads_down state.
    Status,
    /// Engine capability probe.
    Probe,
}

fn parse_kv(s: &str) -> Result<(String, String), String> {
    let (k, v) = s.split_once('=').ok_or("expected key=value")?;
    if k.is_empty() {
        return Err("empty key".into());
    }
    Ok((k.to_string(), v.to_string()))
}

#[tokio::main(flavor = "multi_thread", worker_threads = 4)]
async fn main() -> Result<()> {
    // One process-wide TLS crypto provider for keyed https RPCs (as hd-crank does).
    let _ = rustls::crypto::ring::default_provider().install_default();
    let cli = Cli::parse();
    let cluster = cli.cluster;
    let rpc = cli.rpc.clone().unwrap_or_else(|| cluster.default_rpc().to_string());
    match cli.cmd {
        Cmd::Genesis { fixtures, out, entropy_secret, round_slots, intermission_slots } => {
            genesis::genesis(&genesis::GenesisOpts {
                fixtures,
                out,
                entropy_secret,
                timing: genesis::Timing { round_slots, intermission_slots },
            })
        }
        Cmd::Surgery { entropy_secret, round_slots, intermission_slots } => {
            require_local(cluster, "surgery")?;
            genesis::surgery(&rpc, &entropy_secret, genesis::Timing { round_slots, intermission_slots }).await
        }
        Cmd::Init {
            authority,
            governance,
            registrar,
            executor_fee,
            crank_fee,
            bury_bps,
            executor_float,
            crank_reserve_digs,
            cu_price,
            yes,
            receipt,
        } => {
            ops::init(ops::InitOpts {
                cluster,
                rpc,
                authority,
                governance,
                registrar,
                executor_fee,
                crank_fee,
                bury_bps,
                executor_float,
                crank_reserve_digs,
                cu_price,
                yes,
                receipt,
            })
            .await
        }
        Cmd::Preflight {
            so,
            max_len,
            program_id,
            deployer,
            mode,
            executor_fee,
            crank_fee,
            executor_float,
            crank_reserve_digs,
            fee_budget,
            fee_per_tx,
            buffer,
            keys,
            allow_simd0500_pending,
            sample_txs,
            json,
        } => {
            let go = ops::preflight(ops::PreflightOpts {
                cluster,
                rpc,
                so,
                max_len,
                program_id,
                deployer,
                mode,
                executor_fee,
                crank_fee,
                executor_float,
                crank_reserve_digs,
                fee_budget,
                fee_per_tx,
                buffer,
                keys,
                allow_simd0500_pending,
                sample_txs,
                json,
            })
            .await?;
            if !go {
                std::process::exit(1);
            }
            Ok(())
        }
        Cmd::Funding { deployer, so_len, max_len, crank_fee, executor_float, crank_reserve_digs, fee_budget, keys, json } => {
            ops::funding(ops::FundingOpts {
                cluster,
                rpc,
                deployer,
                so_len,
                max_len,
                crank_fee,
                executor_float,
                crank_reserve_digs,
                fee_budget,
                keys,
                json,
            })
            .await
        }
        Cmd::WriteBuffer { so, buffer, authority, mode, max_len, rate, cu_price, yes, json } => {
            buffer::write_buffer(buffer::WriteBufferOpts { cluster, rpc, so, buffer, authority, mode, max_len, rate, cu_price, yes, json }).await
        }
        Cmd::BufferStatus { so, buffer, deployer, json } => {
            buffer::buffer_status(buffer::BufferStatusOpts { cluster, rpc, so, buffer, deployer, json }).await
        }
        Cmd::Fees { payer, since_slot, json } => ops::fees(ops::FeesOpts { cluster, rpc, payer, since_slot, json }).await,
        Cmd::VerifyDeploy {
            mode,
            so,
            program_id,
            deployer,
            authority,
            buffer,
            signature,
            max_len,
            balance_before,
            buffer_write,
            cli_since_slot,
            meta,
            out,
        } => {
            ops::verify_deploy(ops::VerifyOpts {
                cluster,
                rpc,
                mode,
                so,
                program_id,
                deployer,
                authority,
                buffer,
                signature,
                max_len,
                balance_before,
                buffer_write,
                cli_since_slot,
                meta,
                out,
            })
            .await
        }
        Cmd::ProposeConfig { governance, registrar, crank_fee, bury_bps, paused, cu_price, yes } => {
            ops::propose(ops::ProposeOpts { cluster, rpc, governance, registrar, crank_fee, bury_bps, paused, cu_price, yes }).await
        }
        Cmd::ApplyConfig { payer, cu_price, yes } => ops::apply(ops::ApplyOpts { cluster, rpc, payer, cu_price, yes }).await,
        Cmd::Driver { payer, entropy_secret, background, background_lamports, start_delay_slots, rounds } => {
            require_local(cluster, "driver")?;
            driver::run(driver::DriverOpts { rpc, payer, entropy_secret, background, background_lamports, start_delay_slots, rounds }).await
        }
        Cmd::Smoke { crank_ws, crank_http, indexer, timeout_secs } => {
            require_local(cluster, "smoke")?;
            cluster::connect(cluster, &rpc).await?;
            smoke::run(smoke::SmokeOpts { rpc, crank_ws, crank_http, indexer, timeout: Duration::from_secs(timeout_secs) }).await
        }
        Cmd::ClockIn { wallet, p256, lease, hours } => {
            require_local(cluster, "clock-in")?;
            cluster::connect(cluster, &rpc).await?;
            clockin::run(&rpc, &wallet, &p256, lease, hours).await
        }
        Cmd::Fund { to, sol } => {
            require_local(cluster, "fund")?;
            cluster::connect(cluster, &rpc).await?;
            admin::fund(&rpc, &to, sol).await
        }
        Cmd::Status => {
            cluster::connect(cluster, &rpc).await?;
            admin::status(&rpc).await
        }
        Cmd::Probe => {
            require_local(cluster, "probe")?;
            cluster::connect(cluster, &rpc).await?;
            admin::probe(&rpc).await
        }
    }
}
