"use client";

import { useEffect, useId, useRef, useState, type ReactNode } from "react";

export interface Column {
  key: string;
  /** Axis label (short). */
  label: string;
  /** Stacked segments, bottom first. null/empty = no data for this column. */
  segments: { value: number; series: "guest" | "seeker" | "haul" | "context" }[] | null;
  /** Tooltip / table content. */
  detail: string;
}

export interface ColumnChartProps {
  title: string;
  columns: Column[];
  formatTick: (v: number) => string;
  /** Show every n-th x label (keeps 24-30 columns legible on phones). */
  labelEvery?: number;
  legend?: { series: "guest" | "seeker" | "haul" | "context"; label: string }[];
  tableHeaders: [string, string];
  footer?: ReactNode;
  height?: number;
}

const DEFAULT_WIDTH = 640;
const PAD = { top: 12, right: 8, bottom: 26, left: 44 };

function niceMax(v: number): number {
  if (v <= 0) return 1;
  const exp = Math.pow(10, Math.floor(Math.log10(v)));
  for (const m of [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (m * exp >= v) return m * exp;
  return 10 * exp;
}

/**
 * Accessible SVG column chart (dataviz mark specs): columns <= 24px with a 4px rounded data
 * end, hairline solid grid, 2px surface gap between stacked segments, hover AND keyboard-focus
 * tooltip, and a table view, so no value depends on color or pointer alone.
 */
export function ColumnChart({ title, columns, formatTick, labelEvery = 1, legend, tableHeaders, footer, height = 220 }: ColumnChartProps) {
  const id = useId();
  const [hover, setHover] = useState<number | null>(null);
  // Render at the container's real pixel width (not viewBox scaling), so 11px tick text stays
  // 11px on a phone and on a wide screen alike.
  const box = useRef<HTMLDivElement>(null);
  const [W, setW] = useState(DEFAULT_WIDTH);
  useEffect(() => {
    const el = box.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(([e]) => {
      const w = Math.round(e?.contentRect.width ?? 0);
      if (w > 0) setW(Math.max(240, w));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const totals = columns.map((c) => (c.segments ? c.segments.reduce((a, s) => a + s.value, 0) : 0));
  const max = niceMax(Math.max(0, ...totals));
  const innerW = W - PAD.left - PAD.right;
  const innerH = height - PAD.top - PAD.bottom;
  const band = innerW / Math.max(1, columns.length);
  const barW = Math.min(24, band * 0.7);
  const y = (v: number) => PAD.top + innerH - (v / max) * innerH;
  const ticks = [0, max / 2, max];
  const empty = totals.every((t) => t === 0);

  return (
    <figure className="chart" style={{ margin: 0 }} aria-labelledby={`${id}-t`}>
      <figcaption id={`${id}-t`} className="small muted" style={{ marginBottom: 4 }}>
        {title}
      </figcaption>
      {legend && legend.length > 1 ? (
        <div className="legend">
          {legend.map((l) => (
            <span key={l.series}>
              <i className={`swatch sw-${l.series}`} aria-hidden="true" />
              {l.label}
            </span>
          ))}
        </div>
      ) : null}
      <div ref={box} style={{ width: "100%" }}>
      <svg width={W} height={height} viewBox={`0 0 ${W} ${height}`} role="group" aria-label={title} onMouseLeave={() => setHover(null)}>
        {ticks.map((t) => (
          <g key={t}>
            <line className="gridline" x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} />
            <text className="tick" x={PAD.left - 6} y={y(t) + 4} textAnchor="end">
              {formatTick(t)}
            </text>
          </g>
        ))}
        {columns.map((c, i) => {
          const cx = PAD.left + band * i + band / 2;
          let acc = 0;
          const segs = (c.segments ?? []).filter((s) => s.value > 0);
          return (
            <g
              key={c.key}
              className="bar"
              tabIndex={0}
              role="img"
              aria-label={`${c.label}: ${c.detail}`}
              onMouseEnter={() => setHover(i)}
              onFocus={() => setHover(i)}
              onBlur={() => setHover(null)}
            >
              {/* hit target larger than the mark */}
              <rect x={cx - band / 2} y={PAD.top} width={band} height={innerH} fill="transparent" />
              {segs.map((s, j) => {
                const y0 = y(acc);
                acc += s.value;
                const y1 = y(acc);
                const top = j === segs.length - 1;
                const gap = j > 0 ? 2 : 0; // 2px surface gap between stacked segments
                const h = Math.max(0, y0 - y1 - gap);
                const r = top ? Math.min(4, h, barW / 2) : 0;
                const x0 = cx - barW / 2;
                const yb = y0 - gap;
                const d = `M${x0},${yb} V${yb - h + r} Q${x0},${yb - h} ${x0 + r},${yb - h} H${x0 + barW - r} Q${x0 + barW},${yb - h} ${x0 + barW},${yb - h + r} V${yb} Z`;
                return <path key={j} className="mark" d={d} fill={`var(--viz-${s.series})`} />;
              })}
              {i % labelEvery === 0 ? (
                <text className="tick" x={cx} y={height - 8} textAnchor="middle">
                  {c.label}
                </text>
              ) : null}
            </g>
          );
        })}
        <line className="gridline" x1={PAD.left} x2={W - PAD.right} y1={y(0)} y2={y(0)} style={{ stroke: "var(--text-muted)" }} />
      </svg>
      </div>
      {hover !== null && columns[hover] ? (
        <div
          className="tooltip"
          role="status"
          style={{ left: `${((PAD.left + band * hover + band / 2) / W) * 100}%`, top: `${(y(totals[hover] ?? 0) / height) * 100}%` }}
        >
          <strong>{columns[hover]!.label}</strong>
          <br />
          {columns[hover]!.detail}
        </div>
      ) : null}
      {empty ? <p className="small muted">No data in this window yet.</p> : null}
      <details className="table-view">
        <summary>Show as table</summary>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">{tableHeaders[0]}</th>
                <th scope="col">{tableHeaders[1]}</th>
              </tr>
            </thead>
            <tbody>
              {columns.map((c) => (
                <tr key={c.key}>
                  <td>{c.label}</td>
                  <td>{c.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
      {footer}
    </figure>
  );
}
