import { formatInt, formatPct } from "@/lib/format";
import type { CohortReport } from "@/lib/types";

/**
 * Retention heatmap as a real <table>: one ember hue, more retained = more saturated
 * (sequential, single hue), and every cell prints its value, so nothing depends on color.
 * Immature cells (night cohort+k not complete yet) are hatched and read "not yet".
 */
export function CohortTable({ report }: { report: CohortReport }) {
  const days = report.average.map((a) => a.day);
  if (report.cohorts.length === 0) {
    return <p className="muted">No cohorts yet: a cohort starts on a rig&apos;s first armed shift.</p>;
  }
  return (
    <div className="table-scroll">
      <table>
        <caption className="small muted" style={{ textAlign: "left", captionSide: "bottom", paddingTop: 8 }}>
          Cohort = night of a rig&apos;s first armed shift. Dk = share of that cohort active on night +k exactly.
          Hatched cells are not complete yet.
        </caption>
        <thead>
          <tr>
            <th scope="col">First-shift night</th>
            <th scope="col" className="num">
              Rigs
            </th>
            {days.map((d) => (
              <th key={d} scope="col" className="num">
                D{d}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {report.cohorts.map((c) => (
            <tr key={c.cohort}>
              <th scope="row" className="mono" style={{ fontWeight: 500 }}>
                {c.cohort}
              </th>
              <td className="num">{formatInt(c.size)}</td>
              {c.cells.map((cell) => {
                if (!cell.mature || cell.rate === null) {
                  return (
                    <td key={cell.day} className="heat immature" aria-label={`D${cell.day}: not complete yet`}>
                      not yet
                    </td>
                  );
                }
                const pct = Math.round(cell.rate * 100);
                const strong = cell.rate >= 0.55;
                return (
                  <td
                    key={cell.day}
                    className="heat"
                    style={{
                      background: `color-mix(in oklab, var(--viz-guest) ${12 + Math.round(cell.rate * 88)}%, var(--surface))`,
                      color: strong ? "var(--heat-on)" : "var(--text)",
                      fontWeight: strong ? 650 : 500,
                    }}
                    title={`${cell.retained} of ${c.size} rigs active on night +${cell.day}`}
                  >
                    {pct}%
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">All mature cohorts</th>
            <td className="num">{formatInt(report.average[0]?.rigs ?? 0)}</td>
            {report.average.map((a) => (
              <td key={a.day} className="num" style={{ fontWeight: 650 }}>
                {formatPct(a.rate, 0)}
              </td>
            ))}
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
