//! # hd-crank
//!
//! The permissionless dig crank for Heads Down. It has **liveness only**: it can choose
//! whether and when to submit a phone's heartbeat, but the `heads_down` program computes
//! every amount and square, checks every cap and the cost gate, and verifies every P-256
//! heartbeat through the secp256r1 precompile. A malicious or broken crank can make rigs
//! miss rounds; it cannot move a lamport anywhere ORE and the program would not.
//!
//! Layers (each one testable on its own):
//!
//! | Module | Role |
//! |---|---|
//! | [`ore`], [`hd`] | byte-exact ORE and `heads_down` v1.1 layouts, PDAs, instruction builders, events |
//! | [`gate`] | the on-chain Motherlode-aware cost gate, integer for integer |
//! | [`heartbeat`] | off-chain P-256 verification of phone heartbeats and BREAK / FREEZE, low-S, latest-per-rig store |
//! | [`intake`] | axum WebSocket intake (contract A) with per-IP / per-rig rate limits and backpressure |
//! | [`signal`] | BREAK / FREEZE idempotency, per-rig limits, fee budget and the landing queue |
//! | [`chain`] | ORE Board / Treasury / Round watcher behind the [`chain::ChainSource`] trait |
//! | [`planner`] | pre-checks everything the program checks: which rigs dig, which heartbeats to record |
//! | [`tx`], [`alt`] | batched v0 (+ lookup table) / v1 / legacy transactions, packed under the size limit |
//! | [`sender`], [`ledger`] | submit, confirm, retry with a fresh blockhash, idempotent per (rig, round) |
//! | [`breaker`], [`metrics`] | circuit breaker on ORE layout drift; Prometheus text metrics |
//! | [`crank`] | the loop: digs, `record_heartbeats`, BREAK / FREEZE landing, permissionless `end_shift` |

#![forbid(unsafe_code)]
#![deny(missing_docs)]

pub mod account;
pub mod alt;
pub mod app;
pub mod breaker;
pub mod bytes;
pub mod chain;
pub mod config;
pub mod crank;
pub mod gate;
pub mod hd;
pub mod heartbeat;
pub mod intake;
pub mod keys;
pub mod ledger;
pub mod metrics;
pub mod mirror;
pub mod ore;
pub mod planner;
pub mod ratelimit;
pub mod rpc;
pub mod sender;
pub mod signal;
pub mod tx;
