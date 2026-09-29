"use client";

import { Loadable } from "@/components/Chrome";
import { MilestoneList } from "@/components/MilestoneList";
import { client } from "@/lib/api";
import { useApi, useDataset } from "@/lib/useApi";
import type { Milestones } from "@/lib/types";

const EXPORTS = [
  { path: "/v1/export/rounds.csv?days=30", label: "Per-round share of ORE miners (30 days)" },
  { path: "/v1/export/digs.csv?days=30", label: "Every dig with its signature (30 days)" },
  { path: "/v1/export/monthly.csv", label: "Monthly milestone report" },
];

export default function MilestonesPage() {
  const state = useApi<Milestones>("/v1/milestones");
  const dataset = useDataset();
  return (
    <>
      <h1>ORE milestones</h1>
      <p className="lede">
        The milestones proposed to ORE for the matched prize (docs/ORE.md §9), against what the chain shows today. Every
        figure can be recomputed from ORE&apos;s own events, without trusting this site.
      </p>
      <Loadable state={state}>{(d) => <MilestoneList data={d} />}</Loadable>
      <section className="card section" aria-label="CSV downloads">
        <h2>CSV for milestone reporting</h2>
        <p className="small muted" style={{ marginTop: 0 }}>
          Every row starts with the dataset name and carries the transaction signatures needed to check it.
          {dataset?.simulated ? " These files are SIMULATED and are named that way." : ""}
        </p>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
          {EXPORTS.map((e) => {
            const href = client.url(e.path);
            return href ? (
              <a key={e.path} className="btn" href={href} download>
                ⬇ {e.label}
                {dataset?.simulated ? <span className="sim-badge">SIM</span> : null}
              </a>
            ) : (
              <span key={e.path} className="btn muted" aria-disabled="true">
                {e.label} (API not configured)
              </span>
            );
          })}
        </div>
      </section>
    </>
  );
}
