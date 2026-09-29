"use client";

import { ColumnChart } from "./ColumnChart";
import { EvidenceLinks } from "./Evidence";
import { StatTile } from "./StatTile";
import { formatHours, formatInt, formatOre, formatPct, formatSol, formatTz } from "@/lib/format";
import type { Summary } from "@/lib/types";

const CHECK_LABELS: Record<keyof Summary["consistency"], string> = {
  digsWithoutDeploy: "Digs without a matching ORE DeployEvent",
  deploysWithoutDig: "Executor DeployEvents without a RigDug",
  lamportsMismatch: "RigDug SOL ≠ DeployEvent SOL",
  roundsDugExceedsDark: "Shifts with more digs than dark rounds",
  seekerTierWithoutSeat: "Seeker-tier rigs without a SeekerSeat",
  hdMinersExceedTotal: "Rounds with more Heads Down miners than ORE miners",
};

export function OverviewView({ s, simulated }: { s: Summary; simulated: boolean }) {
  const seekerShare = s.rigs.total > 0 ? s.rigs.seeker / s.rigs.total : null;
  const checks = Object.entries(s.consistency) as [keyof Summary["consistency"], number][];
  const allClean = checks.every(([, n]) => n === 0) && s.solDeployed.consistent;
  return (
    <>
      <div className="grid tiles">
        <StatTile label="Rigs" value={formatInt(s.rigs.total)} sub={s.rigs.basis === "accounts" ? "Open Rig accounts" : "Distinct rigs seen in events"} evidence={s.rigs.evidence} simulated={simulated} />
        <StatTile label="Seeker-verified rigs" swatch="seeker" value={formatInt(s.rigs.seeker)} sub={`${formatPct(seekerShare, 0)} of rigs · SGT checked on-chain`} simulated={simulated} />
        <StatTile label="Guest rigs" swatch="guest" value={formatInt(s.rigs.guest)} sub="Any Android phone" simulated={simulated} />
        <StatTile
          label="Nightly active rigs"
          value={formatInt(s.nightlyActive.rigs)}
          sub={`Night of ${s.nightlyActive.lastNight ?? "—"} · 30-night peak ${formatInt(s.nightlyActive.peak)}`}
          evidence={s.nightlyActive.evidence}
          simulated={simulated}
        />
        <StatTile
          label="Dark hours"
          value={formatHours(s.darkHours.hours)}
          sub={`${formatInt(Number(s.darkHours.darkRounds))} heartbeat-leased rounds × ${Math.round(s.darkHours.roundSeconds)} s${s.darkHours.roundSecondsBasis === "fallback" ? " (estimated)" : ""}`}
          evidence={s.darkHours.evidence}
          simulated={simulated}
        />
        <StatTile
          label="Rounds dug"
          value={formatInt(s.roundsDug.rigRounds)}
          sub={`Rig-rounds, across ${formatInt(s.roundsDug.distinctRounds)} ORE rounds`}
          evidence={s.roundsDug.evidence}
          simulated={simulated}
        />
        <StatTile
          label="SOL deployed into ORE"
          value={formatSol(s.solDeployed.lamports, 2)}
          sub={s.solDeployed.consistent ? "Matches ORE's own DeployEvents ✓" : `ORE DeployEvents say ${formatSol(s.solDeployed.oreDeployEventLamports, 3)}`}
          evidence={s.solDeployed.evidence}
          simulated={simulated}
        />
        <StatTile
          label="ORE mined"
          swatch="haul"
          value={formatOre(s.ore.mined.amount, 3)}
          sub={`Unrefined, before ORE's 10% refining fee${s.ore.mined.digsPendingRound ? ` · ${s.ore.mined.digsPendingRound} digs awaiting round data` : ""}`}
          evidence={s.ore.mined.evidence}
          simulated={simulated}
        />
        <StatTile label="ORE bought" swatch="haul" value={null} notShipped={s.ore.bought.note} simulated={simulated} />
        <StatTile label="ORE buried" swatch="haul" value={null} notShipped={s.ore.buried.note} simulated={simulated} />
        <StatTile
          label="Gate-open rate"
          value={formatPct(s.gate.openRate, 1)}
          sub={`${formatInt(s.gate.opened)} digs vs ${formatInt(s.gate.closedByCostGate)} cost-gate refusals on-chain · dug in ${formatPct(s.gate.digShareOfDarkRounds, 1)} of dark rounds`}
          evidence={s.gate.evidence}
          simulated={simulated}
        />
        <StatTile
          label="Cranks landing digs"
          value={formatInt(s.crankers.distinct)}
          sub={s.crankers.thirdParty === null ? "Distinct fee payers" : `${formatInt(s.crankers.thirdParty)} third-party`}
          simulated={simulated}
        />
      </div>

      <section className="card section" aria-label="Nightly active rigs, last 30 nights">
        <h2>Nightly active rigs</h2>
        <p className="small muted" style={{ marginTop: 0 }}>
          A rig is active on a night if it armed a shift, dug, or ended a shift with dark rounds. Nights run noon to noon,{" "}
          {formatTz(s.tzOffsetMinutes)}.
        </p>
        <ColumnChart
          title="Active rigs per night, Seeker-verified and guest"
          columns={s.nightlyActive.series.map((n) => ({
            key: n.night,
            label: n.night.slice(5),
            segments: [
              { value: n.seeker, series: "seeker" as const },
              { value: n.rigs - n.seeker, series: "guest" as const },
            ],
            detail: `${n.rigs} active (${n.seeker} Seeker, ${n.rigs - n.seeker} guest)`,
          }))}
          formatTick={(v) => formatInt(v)}
          labelEvery={5}
          legend={[
            { series: "seeker", label: "Seeker-verified" },
            { series: "guest", label: "Guest" },
          ]}
          tableHeaders={["Night", "Active rigs"]}
        />
      </section>

      <section className="card section" aria-label="Integrity checks">
        <h2>Integrity checks {allClean ? <span className="pill-ok small">all clear</span> : <span className="pill-warn small">needs attention</span>}</h2>
        <p className="small muted" style={{ marginTop: 0 }}>
          Cross-checks between the heads_down program&apos;s events, ORE&apos;s own events and account state. They are published
          whatever they show.
        </p>
        <div className="table-scroll">
          <table>
            <tbody>
              {checks.map(([k, n]) => (
                <tr key={k}>
                  <td>{CHECK_LABELS[k]}</td>
                  <td className="num">
                    <span className={n === 0 ? "pill-ok" : "pill-warn"}>{n === 0 ? "0 ✓" : `${formatInt(n)} ⚠`}</span>
                  </td>
                </tr>
              ))}
              <tr>
                <td>Program circuit breaker (Config.paused)</td>
                <td className="num">{s.programPaused === null ? "unknown" : s.programPaused ? "PAUSED" : "running"}</td>
              </tr>
              {s.skips.map((k) => (
                <tr key={k.code}>
                  <td>
                    Rigs skipped: <span className="mono">{k.name}</span>
                  </td>
                  <td className="num">{formatInt(k.count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <EvidenceLinks items={s.solDeployed.evidence} simulated={simulated} />
      </section>
    </>
  );
}
