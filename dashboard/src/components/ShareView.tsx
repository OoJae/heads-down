"use client";

import { ColumnChart } from "./ColumnChart";
import { ExplorerLink } from "./Evidence";
import { formatInt, formatPct, formatTz } from "@/lib/format";
import type { ShareByHour } from "@/lib/types";

/** Hours that ORE milestone M2 measures (00:00-06:00 local). */
export const MILESTONE_HOURS = new Set([0, 1, 2, 3, 4, 5]);

export function ShareView({ data, simulated }: { data: ShareByHour; simulated: boolean }) {
  const peaks = data.hours.filter((h) => h.peak);
  return (
    <>
      <section className="card" aria-label="Share of ORE miners by hour">
        <h2>
          Heads Down share of ORE miners: {formatPct(data.meanShare, 2)} <span className="muted small">mean per round</span>
        </h2>
        <p className="small muted" style={{ marginTop: 0 }}>
          {formatInt(data.rounds)} ORE rounds in the last {data.windowDays} day{data.windowDays === 1 ? "" : "s"}, of which{" "}
          {formatInt(data.roundsWithHd)} had at least one Heads Down rig. Share = distinct Heads Down wallets that deployed ÷
          ORE&apos;s own <span className="mono">total_miners</span> for the round. Hours are {formatTz(data.tzOffsetMinutes)}.
        </p>
        <ColumnChart
          title={`Mean share per round, by hour of day (${formatTz(data.tzOffsetMinutes)})`}
          columns={data.hours.map((h) => ({
            key: String(h.hour),
            label: String(h.hour).padStart(2, "0"),
            segments: h.meanShare === null ? null : [{ value: h.meanShare, series: MILESTONE_HOURS.has(h.hour) ? ("guest" as const) : ("context" as const) }],
            detail:
              h.meanShare === null
                ? "no ORE rounds"
                : `${formatPct(h.meanShare, 2)} mean · ${h.meanHdMiners?.toFixed(1)} of ${h.meanTotalMiners?.toFixed(0)} miners · peak ${formatPct(h.maxShare, 1)} · ${formatInt(h.rounds)} rounds`,
          }))}
          formatTick={(v) => formatPct(v, v < 0.1 ? 1 : 0)}
          labelEvery={3}
          legend={[
            { series: "guest", label: "00:00–06:00, the milestone window" },
            { series: "context", label: "Other hours" },
          ]}
          tableHeaders={["Hour", "Share of ORE miners"]}
        />
      </section>
      <section className="card section" aria-label="Peak rounds">
        <h2>Check it yourself</h2>
        <p className="small muted" style={{ marginTop: 0 }}>
          For each hour, the round with the highest share. Its reset transaction carries ORE&apos;s{" "}
          <span className="mono">ResetEvent.total_miners</span>, and the dig carries ORE&apos;s DeployEvent with{" "}
          <span className="mono">signer</span> = the Heads Down Executor PDA.
        </p>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">Hour</th>
                <th scope="col" className="num">
                  Round
                </th>
                <th scope="col" className="num">
                  Heads Down / ORE miners
                </th>
                <th scope="col" className="num">
                  Share
                </th>
                <th scope="col">ORE reset tx</th>
                <th scope="col">Sample dig tx</th>
              </tr>
            </thead>
            <tbody>
              {peaks.map((h) => (
                <tr key={h.hour}>
                  <td>{String(h.hour).padStart(2, "0")}:00</td>
                  <td className="num mono">{h.peak!.roundId}</td>
                  <td className="num">
                    {h.peak!.hdMiners} / {h.peak!.totalMiners}
                  </td>
                  <td className="num">{formatPct(h.peak!.share, 1)}</td>
                  <td>{h.peak!.resetSignature ? <ExplorerLink url={h.peak!.resetUrl} id={h.peak!.resetSignature} simulated={simulated} /> : "—"}</td>
                  <td>{h.peak!.sampleDig ? <ExplorerLink url={h.peak!.digUrl} id={h.peak!.sampleDig} simulated={simulated} /> : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}
