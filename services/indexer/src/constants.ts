/**
 * Pinned addresses (programs/heads-down/INTERFACE.md, docs/ORE.md).
 *
 * The Executor PDA is pinned here AND re-derived at startup ({@link deriveExecutorPda});
 * `assertPinnedPdas` refuses to start if they disagree, so a typo can never make the
 * indexer attribute some other signer's ORE deploys to Heads Down.
 */
import { getProgramDerivedAddress, type Address } from "@solana/addresses";

export const HEADS_DOWN_PROGRAM_ID = "HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p";

/** `[b"executor"]` under HEADS_DOWN_PROGRAM_ID (`solana find-program-derived-address ... string:executor`). */
export const EXECUTOR_PDA = "By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge";
export const EXECUTOR_BUMP = 249;
/** `[b"config"]` under HEADS_DOWN_PROGRAM_ID. */
export const CONFIG_PDA = "inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW";
export const CONFIG_BUMP = 253;

export const ORE_PROGRAM_ID = "oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv";
export const ORE_BOARD = "BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi";
export const ORE_CONFIG = "9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy";
export const ORE_TREASURY = "45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG";
/** Written into Round.top_miner / ResetEvent.top_miner when the +1 ORE is split pro rata. */
export const ORE_SPLIT_ADDRESS = "SpLiT11111111111111111111111111111111111112";

/** ORE has 11 decimals. */
export const ONE_ORE = 100_000_000_000n;
export const LAMPORTS_PER_SOL = 1_000_000_000n;

/** ORE `Log` instruction tag (api/src/instruction.rs). Event bytes follow the tag. */
export const ORE_LOG_IX_TAG = 8;
/** ORE event discriminators (api/src/event.rs, `OreEvent`). */
export const ORE_EVENT_RESET = 0n;
export const ORE_EVENT_DEPLOY = 2n;
/** ORE Discretionary automation strategy. */
export const ORE_STRATEGY_DISCRETIONARY = 2n;

/** Measured median ORE round length (docs/ORE.md; ResetEvent ts deltas). Fallback only. */
export const DEFAULT_ROUND_SECONDS = 78;

export async function derivePda(programId: string, seed: string): Promise<{ address: string; bump: number }> {
  const [address, bump] = await getProgramDerivedAddress({
    programAddress: programId as Address,
    seeds: [new TextEncoder().encode(seed)],
  });
  return { address, bump };
}

export async function deriveExecutorPda(programId: string = HEADS_DOWN_PROGRAM_ID) {
  return derivePda(programId, "executor");
}

/**
 * Resolves the Executor PDA for `programId`. For the real program id the result must equal
 * the pinned constant; any mismatch is a build/config error, not something to tolerate.
 */
export async function resolveExecutorPda(programId: string = HEADS_DOWN_PROGRAM_ID): Promise<string> {
  const { address, bump } = await deriveExecutorPda(programId);
  if (programId === HEADS_DOWN_PROGRAM_ID && (address !== EXECUTOR_PDA || bump !== EXECUTOR_BUMP)) {
    throw new Error(`Executor PDA mismatch: derived ${address}/${bump}, pinned ${EXECUTOR_PDA}/${EXECUTOR_BUMP}`);
  }
  return address;
}
