import { safeExplorerUrl } from "@/lib/api";
import { shortId } from "@/lib/format";
import type { Evidence } from "@/lib/types";

/**
 * "Verify" links for a metric. Real datasets link to an allowlisted explorer. Simulated data
 * has no on-chain record, so it says so instead of linking anywhere.
 */
export function EvidenceLinks({ items, simulated, max = 3 }: { items: Evidence[]; simulated: boolean; max?: number }) {
  if (items.length === 0) return null;
  return (
    <div className="evidence" aria-label="On-chain evidence">
      <span className="muted">Verify:</span>
      {items.slice(0, max).map((e, i) => (
        <ExplorerLink key={`${e.id}-${i}`} url={e.url} id={e.id} label={e.label} simulated={simulated} />
      ))}
    </div>
  );
}

export function ExplorerLink({ url, id, label, simulated }: { url: string | null; id: string; label?: string; simulated: boolean }) {
  const safe = simulated ? null : safeExplorerUrl(url);
  const text = label ? `${label} ${shortId(id)}` : shortId(id);
  if (!safe) {
    return (
      <span className="noproof mono" title={simulated ? "Simulated: this id exists on no chain" : "No verified explorer link"}>
        {text}
        {simulated ? " (sim)" : ""}
      </span>
    );
  }
  return (
    <a className="mono" href={safe} target="_blank" rel="noopener noreferrer" title={`${label ?? ""} ${id}`.trim()}>
      {text} ↗
    </a>
  );
}
