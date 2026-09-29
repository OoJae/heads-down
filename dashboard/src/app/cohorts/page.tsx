"use client";

import { Loadable } from "@/components/Chrome";
import { CohortTable } from "@/components/CohortTable";
import { StatTile } from "@/components/StatTile";
import { formatInt, formatPct } from "@/lib/format";
import { useApi } from "@/lib/useApi";
import type { CohortReport } from "@/lib/types";

export default function CohortsPage() {
  const state = useApi<CohortReport>("/v1/cohorts");
  return (
    <>
      <h1>Retention by first shift</h1>
      <p className="lede">
        Do people keep putting their phone down? Each cohort is the set of rigs whose first armed shift fell on that
        night. D1, D7 and D14 are the share of the cohort that was active exactly 1, 7 and 14 nights later.
      </p>
      <Loadable state={state}>
        {(r, simulated) => (
          <>
            <div className="grid tiles">
              {r.average.map((a) => (
                <StatTile
                  key={a.day}
                  label={`D${a.day} retention`}
                  value={formatPct(a.rate, 0)}
                  sub={a.cohorts > 0 ? `${formatInt(a.rigs)} rigs in ${formatInt(a.cohorts)} complete cohorts` : "No complete cohort yet"}
                  simulated={simulated}
                />
              ))}
            </div>
            <section className="card section" aria-label="Cohort table">
              <h2>Cohorts</h2>
              <CohortTable report={r} />
            </section>
          </>
        )}
      </Loadable>
    </>
  );
}
