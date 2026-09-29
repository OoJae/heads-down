/**
 * Helius *raw* webhook receiver (transaction type ANY, account addresses = the heads_down
 * program id and the Executor PDA). Helius POSTs a JSON array of transactions in the same
 * shape as `getTransaction`.
 *
 * Trust: the Authorization header must equal the secret configured on the webhook (compared
 * in constant time). Even an authenticated payload is treated as a HINT by default: only the
 * signatures are taken from it and each transaction is re-fetched from our RPC at `finalized`
 * commitment, so a leaked webhook secret cannot inject fabricated digs. Set
 * `trustPayload: true` only when the RPC is Helius itself and the extra reads are unwanted.
 */
import { timingSafeEqual, createHash } from "node:crypto";
import { isSignature } from "../codec/base58.ts";
import type { RawTransaction } from "../codec/tx.ts";
import { ingestRawTransactions, type IngestContext } from "../ingest.ts";
import { mapLimit, type RpcClient } from "./rpc.ts";

export const MAX_WEBHOOK_BYTES = 5 * 1024 * 1024;
export const MAX_WEBHOOK_TXS = 200;

export function checkWebhookAuth(header: string | undefined, secret: string): boolean {
  if (!header || !secret) return false;
  // Hash both sides so the comparison is constant-time regardless of length.
  const a = createHash("sha256").update(header).digest();
  const b = createHash("sha256").update(secret).digest();
  return timingSafeEqual(a, b);
}

export interface WebhookResult {
  received: number;
  ingested: number;
  rejected: number;
}

export async function handleHeliusPayload(
  ctx: IngestContext,
  body: unknown,
  opts: { rpc: RpcClient | null; trustPayload: boolean },
): Promise<WebhookResult> {
  if (!Array.isArray(body)) throw new Error("webhook body must be a JSON array");
  const items = body.slice(0, MAX_WEBHOOK_TXS);
  if (opts.trustPayload) {
    const n = await ingestRawTransactions(ctx, items as RawTransaction[], "helius-webhook");
    return { received: body.length, ingested: n, rejected: body.length - items.length };
  }
  if (!opts.rpc) throw new Error("webhook verification needs RPC_URL (or set HELIUS_WEBHOOK_TRUST_PAYLOAD=1)");
  const sigs = [
    ...new Set(
      items
        .map((it) => (it as RawTransaction)?.transaction?.signatures?.[0])
        .filter((s): s is string => isSignature(s)),
    ),
  ];
  const rpc = opts.rpc;
  const txs = await mapLimit(sigs, 4, (s) => rpc.getTransaction(s));
  const found = txs.filter((t): t is RawTransaction => t !== null);
  const n = await ingestRawTransactions(ctx, found, "helius-webhook+rpc");
  return { received: body.length, ingested: n, rejected: body.length - found.length };
}
