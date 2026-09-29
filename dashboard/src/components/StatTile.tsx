import type { ReactNode } from "react";
import type { Evidence } from "@/lib/types";
import { EvidenceLinks } from "./Evidence";

export interface StatTileProps {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  /** Identity swatch (the value text itself always stays in text ink). */
  swatch?: "guest" | "seeker" | "haul";
  evidence?: Evidence[];
  simulated: boolean;
  /** For metrics whose feature has not shipped: shown as a status, never as a zero. */
  notShipped?: string;
}

export function StatTile({ label, value, sub, swatch, evidence, simulated, notShipped }: StatTileProps) {
  return (
    <section className="card tile" aria-label={label}>
      <div className="tile-label">
        {swatch ? <span className={`swatch sw-${swatch}`} aria-hidden="true" /> : null}
        <span>{label}</span>
        {simulated ? <span className="sim-badge" title="Simulated data">SIM</span> : null}
      </div>
      {notShipped ? (
        <>
          <div className="tile-value muted">Not shipped</div>
          <span className="tile-status">placeholder</span>
          <div className="tile-sub">{notShipped}</div>
        </>
      ) : (
        <>
          <div className="tile-value">{value}</div>
          {sub ? <div className="tile-sub">{sub}</div> : null}
        </>
      )}
      {evidence ? <EvidenceLinks items={evidence} simulated={simulated} /> : null}
    </section>
  );
}
