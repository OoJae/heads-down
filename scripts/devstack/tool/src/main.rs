//! `hd-devstack`: the Rust half of `scripts/devstack` (see `docs/DEVSTACK.md`).
//!
//! | Subcommand | What |
//! |---|---|
//! | `genesis` | mainnet dump → `--account` files for `solana-test-validator` (minimal surgery) |
//! | `surgery` | the same rewrites applied live on Surfpool (`surfnet_setAccount`) |
//! | `init` | heads_down `initialize_config` + Executor PDA float |
//! | `driver` | the ORE round driver (background miner, entropy reveal, `reset`) |
//! | `smoke` | the phone-less end-to-end "trustless beat" |
//! | `fund` | airdrop SOL on the local validator |
//! | `status` | ORE / heads_down state on the fork |
//! | `probe` | engine capability check (secp256r1 precompile really verifies, v1 getTransaction, SPL Token) |

#![forbid(unsafe_code)]

mod admin;
mod driver;
mod entropy;
mod genesis;
mod hd;
mod ore;
mod phone;
mod smoke;
mod util;

use std::path::PathBuf;
use std::time::Duration;

use anyhow::Result;
use clap::{Parser, Subcommand};
use solana_address::Address;

#[derive(Parser)]
#[command(name = "hd-devstack", about = "Heads Down local mainnet-fork dev stack")]
struct Cli {
    /// Local JSON-RPC URL.
    #[arg(long, global = true, env = "HD_DEVSTACK_RPC", default_value = "http://127.0.0.1:8899")]
    rpc: String,
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
    /// initialize_config + fund the Executor PDA.
    Init {
        #[arg(long)]
        authority: PathBuf,
        #[arg(long)]
        governance: Address,
        #[arg(long)]
        registrar: Address,
        #[arg(long, default_value_t = 10_000)]
        executor_fee: u64,
        #[arg(long, default_value_t = 7_000)]
        crank_fee: u64,
        /// Executor PDA float in lamports.
        #[arg(long, default_value_t = 1_000_000_000)]
        executor_float: u64,
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
    /// Airdrop SOL to a pubkey.
    Fund {
        to: Address,
        #[arg(default_value_t = 10.0)]
        sol: f64,
    },
    /// Print fork state.
    Status,
    /// Engine capability probe.
    Probe,
}

#[tokio::main(flavor = "multi_thread", worker_threads = 4)]
async fn main() -> Result<()> {
    let cli = Cli::parse();
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
            genesis::surgery(&cli.rpc, &entropy_secret, genesis::Timing { round_slots, intermission_slots }).await
        }
        Cmd::Init { authority, governance, registrar, executor_fee, crank_fee, executor_float } => {
            admin::init(admin::InitOpts { rpc: cli.rpc, authority, governance, registrar, executor_fee, crank_fee, executor_float }).await
        }
        Cmd::Driver { payer, entropy_secret, background, background_lamports, start_delay_slots, rounds } => {
            driver::run(driver::DriverOpts { rpc: cli.rpc, payer, entropy_secret, background, background_lamports, start_delay_slots, rounds })
                .await
        }
        Cmd::Smoke { crank_ws, crank_http, indexer, timeout_secs } => {
            smoke::run(smoke::SmokeOpts { rpc: cli.rpc, crank_ws, crank_http, indexer, timeout: Duration::from_secs(timeout_secs) }).await
        }
        Cmd::Fund { to, sol } => admin::fund(&cli.rpc, &to, sol).await,
        Cmd::Status => admin::status(&cli.rpc).await,
        Cmd::Probe => admin::probe(&cli.rpc).await,
    }
}
