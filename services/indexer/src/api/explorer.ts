/**
 * Explorer links. Simulated data gets NO link: its addresses and signatures exist on no chain,
 * and a link would imply otherwise.
 */
import { isAddress, isSignature } from "../codec/base58.ts";
import type { Dataset } from "../model.ts";

export function explorerUrl(dataset: Dataset, kind: "tx" | "account", id: string, localRpc = "http://127.0.0.1:8899"): string | null {
  if (dataset === "simulated") return null;
  if (kind === "tx" ? !isSignature(id) : !isAddress(id)) return null;
  const path = kind === "tx" ? "tx" : "account";
  switch (dataset) {
    case "mainnet":
      return `https://solscan.io/${path}/${id}`;
    case "devnet":
      return `https://solscan.io/${path}/${id}?cluster=devnet`;
    case "localnet":
      return `https://explorer.solana.com/${kind === "tx" ? "tx" : "address"}/${id}?cluster=custom&customUrl=${encodeURIComponent(localRpc)}`;
  }
}
