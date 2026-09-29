import { formatInt, formatPct, formatTz } from "@/lib/format";
import type { MilestoneMetric, Milestones } from "@/lib/types";

function fmt(m: MilestoneMetric, v: number | null) {
  return m.unit === "share" ? formatPct(v, 1) : formatInt(v);
}

function Meter({ m }: { m: MilestoneMetric }) {
  const ratio = m.value === null ? 0 : Math.max(0, Math.min(1, m.value / m.target));
  const met = m.value !== null && m.value >= m.target;
  return (
    <div style={{ marginTop: 12 }}>
      <div style={{ display: "flex", justifyContent: "space-between", gap: 12, flexWrap: "wrap" }}>
        <span>{m.label}</span>
        <span>
          <strong>{fmt(m, m.value)}</strong>
          <span className="muted">
            {" "}
            / target {fmt(m, m.target)}
            {m.stretch !== null ? ` · stretch ${fmt(m, m.stretch)}` : ""}
          </span>
          {met ? <span className="pill-ok"> ✓ met</span> : null}
        </span>
      </div>
      <div
        className={`meter${m.key === "seeker_rigs" ? " seeker" : ""}`}
        role="meter"
        aria-label={m.label}
        aria-valuemin={0}
        aria-valuemax={m.target}
        aria-valuenow={m.value ?? 0}
        aria-valuetext={`${fmt(m, m.value)} of ${fmt(m, m.target)}`}
      >
        <span style={{ width: `${Math.round(ratio * 100)}%` }} />
      </div>
      {m.note ? <div className="small muted">{m.note}</div> : null}
    </div>
  );
}

export function MilestoneList({ data }: { data: Milestones }) {
  return (
    <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))" }}>
      {data.milestones.map((ms) => (
        <section key={ms.id} className="card" aria-label={`${ms.id} ${ms.title}`}>
          <h2>
            {ms.id} · {ms.title}
          </h2>
          <div className="small muted">Deadline: {ms.deadline}</div>
          {ms.metrics.map((m) => (
            <Meter key={m.key} m={m} />
          ))}
          {ms.manual.length > 0 ? (
            <>
              <div className="small" style={{ marginTop: 14, color: "var(--text-2)" }}>
                Checked by hand (not measurable from chain data):
              </div>
              <ul className="small" style={{ margin: "4px 0 0", paddingLeft: 18, color: "var(--text-2)" }}>
                {ms.manual.map((t) => (
                  <li key={t}>{t}</li>
                ))}
              </ul>
            </>
          ) : null}
        </section>
      ))}
      <p className="small muted" style={{ gridColumn: "1 / -1", margin: 0 }}>
        Share window: {String(data.shareWindow.fromHour).padStart(2, "0")}:00–{String(data.shareWindow.toHour).padStart(2, "0")}:00{" "}
        {formatTz(data.tzOffsetMinutes)}, mean over the last {data.shareWindow.nights} complete nights.
      </p>
    </div>
  );
}
