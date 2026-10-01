"use client";

import Link from "next/link";
import { useState } from "react";
import { formatInt, formatPct, formatUtc, shortId, timeAgo } from "@/lib/format";
import type { SkipItem, Skips } from "@/lib/types";
import { ExplorerLink } from "./Evidence";

const hex = (code: number) => `0x${code.toString(16).padStart(8, "0")}`;

/**
 * Why rigs were skipped, by reason: one horizontal bar per error code, one hue (the reasons are
 * nominal, so no ramp), sorted by count. Selecting a reason filters the list below and keeps that
 * bar in ember while the others recede to gray. Every bar is a keyboard-focusable button with its
 * value in text, and the same numbers are in the table view.
 */
export function SkipHistogram({
  histogram,
  selected,
  onSelect,
}: {
  histogram: Skips["histogram"];
  selected: number | null;
  onSelect: (code: number | null) => void;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const total = histogram.reduce((n, h) => n + h.count, 0);
  const max = Math.max(1, ...histogram.map((h) => h.count));
  if (histogram.length === 0) return <p className="muted">No rig has been skipped yet.</p>;
  const focus = histogram.find((h) => h.code === hover);
  return (
    <figure className="hbars" style={{ margin: 0 }} aria-label="Skips by reason">
      <figcaption className="small muted" style={{ marginBottom: 8 }}>
        RigSkipped events by reason ({formatInt(total)} in all). Select a reason to list only those skips.
      </figcaption>
      <div className="hbar-list">
        {histogram.map((h) => {
          const muted = selected !== null && selected !== h.code;
          return (
            <button
              key={h.code}
              type="button"
              className={`hbar-row${muted ? " hbar-muted" : ""}`}
              aria-pressed={selected === h.code}
              aria-label={`${h.name}: ${formatInt(h.count)} skips, ${formatPct(h.count / total, 0)} of all. ${h.label}`}
              onClick={() => onSelect(selected === h.code ? null : h.code)}
              onMouseEnter={() => setHover(h.code)}
              onMouseLeave={() => setHover(null)}
              onFocus={() => setHover(h.code)}
              onBlur={() => setHover(null)}
            >
              <span className="hbar-label">
                <span className="mono">{h.name}</span>
                <span className="small muted">{h.label}</span>
              </span>
              <span className="hbar-track">
                <span className="hbar-fill" style={{ width: `${Math.max(0.5, (h.count / max) * 100)}%` }} />
                <span className="hbar-value">{formatInt(h.count)}</span>
              </span>
            </button>
          );
        })}
      </div>
      <p className="small muted" role="status" style={{ minHeight: "1.5em", margin: "6px 0 0" }}>
        {focus ? `${focus.name} (${hex(focus.code)}, ${focus.range}): ${formatInt(focus.count)} skips, ${formatPct(focus.count / total, 1)} of all. ${focus.label}.` : ""}
      </p>
      <details className="table-view">
        <summary>Show as table</summary>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">Code</th>
                <th scope="col">Name</th>
                <th scope="col">What it means</th>
                <th scope="col" className="num">
                  Skips
                </th>
              </tr>
            </thead>
            <tbody>
              {histogram.map((h) => (
                <tr key={h.code}>
                  <td className="mono">{hex(h.code)}</td>
                  <td className="mono">{h.name}</td>
                  <td>{h.label}</td>
                  <td className="num">{formatInt(h.count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  );
}

export function SkipList({ items, simulated, now }: { items: SkipItem[]; simulated: boolean; now: number }) {
  if (items.length === 0) return <p className="muted">No skips match.</p>;
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">Rig</th>
            <th scope="col" className="num">
              ORE round
            </th>
            <th scope="col">Reason</th>
            <th scope="col">Transaction</th>
          </tr>
        </thead>
        <tbody>
          {items.map((s) => (
            <tr key={`${s.signature}-${s.rig}`}>
              <td title={formatUtc(s.blockTime)}>{timeAgo(s.blockTime, now)}</td>
              <td>
                <Link className="mono" href={`/haul/?rig=${encodeURIComponent(s.rig)}`} title={`Morning haul of rig ${s.rig}`}>
                  {shortId(s.rig)}
                </Link>
              </td>
              <td className="num mono">{s.roundId}</td>
              <td>
                <span className="mono">{s.name}</span> <span className="small muted">· {s.label}</span>
              </td>
              <td>
                <ExplorerLink url={s.txUrl} id={s.signature} simulated={simulated} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
