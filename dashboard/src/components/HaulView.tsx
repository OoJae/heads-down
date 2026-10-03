"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { safeExplorerUrl } from "@/lib/api";
import { formatInt, formatOre, formatSol, shortId } from "@/lib/format";
import {
  breakReasonText,
  clockUtc,
  duration,
  motherlodeNearMiss,
  roundViews,
  streakLine,
  verdict,
  verdictLine,
  type RoundView,
} from "@/lib/haul";
import type { DatasetName, HaulSummary } from "@/lib/types";
import { StatTile } from "./StatTile";

/**
 * Lamports per ORE → SOL per ORE with 3 decimals, rounded half-even: the phone's HaulFormat.price,
 * so a haul reads the same on both. One difference, on purpose: a non-zero price below the display
 * precision shows as "<0.001", never as "0.000".
 */
export function priceSol(lamportsPerOre: string): string {
  // Not bounded by u64: a shift that mined a few atoms of ORE divides its cost by almost nothing.
  if (!/^\d{1,40}$/.test(lamportsPerOre)) return "—";
  const v = BigInt(lamportsPerOre);
  const unit = 1_000_000n; // lamports per 0.001 SOL
  let q = v / unit;
  const r = (v % unit) * 2n;
  if (r > unit || (r === unit && (q & 1n) === 1n)) q += 1n;
  if (q === 0n && v > 0n) return "<0.001";
  return `${q / 1000n}.${(q % 1000n).toString().padStart(3, "0")}`;
}

function popcount(m: number): number {
  let n = 0;
  for (let x = m >>> 0; x; x &= x - 1) n++;
  return n;
}

/** The 5x5 ORE board for one round: ember = dug, ink ring = the winning tile, star = the rig's tile came up. */
function Board({ round }: { round: RoundView | null }) {
  return (
    <div className="board" role="grid" aria-label={round ? `ORE board, round ${round.roundId}` : "ORE board"}>
      {Array.from({ length: 5 }, (_, row) => (
        <div key={row} role="row" className="board-row">
          {Array.from({ length: 5 }, (_, col) => {
            const tile = row * 5 + col;
            const dug = round !== null && ((round.dugMask >>> tile) & 1) === 1;
            const winning = round !== null && round.winningSquare === tile;
            const hit = dug && winning;
            const what = dug ? (winning ? ": the rig dug it, and it won" : ": the rig dug it") : winning ? ": the winning tile" : "";
            const cls = ["sq", dug ? "sq-dug" : "", winning ? "sq-win" : "", hit ? "sq-hit" : ""].filter(Boolean).join(" ");
            return (
              <div key={col} role="gridcell" aria-label={`Tile ${tile + 1}${what}`} className={cls}>
                {hit ? <span aria-hidden="true">★</span> : null}
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}

/**
 * Every ORE round of the shift as one cell: empty = no heartbeat lease covered it, gray = dark
 * (leased) but no dig, ember = dug, ember with an ink ring = the rig's tile came up. Dug rounds
 * are buttons that put that round on the board (hover, focus or click).
 */
function RoundStrip({ rounds, selected, onSelect }: { rounds: RoundView[]; selected: string | null; onSelect: (r: RoundView) => void }) {
  // Real pixel cells (not viewBox scaling), so a phone gets as many 12px cells per row as fit
  // instead of 40 shrunken ones: a bigger hit target for every dug round.
  const box = useRef<HTMLDivElement>(null);
  const [avail, setAvail] = useState(560);
  useEffect(() => {
    const el = box.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(([e]) => {
      const w = Math.round(e?.contentRect.width ?? 0);
      if (w > 0) setAvail(Math.min(560, w));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const cell = 12;
  const gap = 2;
  const perRow = Math.max(10, Math.min(40, Math.floor((avail + gap) / (cell + gap))));
  const rows = Math.max(1, Math.ceil(rounds.length / perRow));
  const width = perRow * (cell + gap) - gap;
  const height = rows * (cell + gap) - gap;
  return (
    <div ref={box} style={{ width: "100%" }}>
    <svg className="round-strip" width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="group" aria-label={`${rounds.length} ORE rounds of the shift`}>
      {rounds.map((r, i) => {
        const x = (i % perRow) * (cell + gap);
        const y = Math.floor(i / perRow) * (cell + gap);
        if (!r.dug) return <rect key={r.roundId} x={x} y={y} width={cell} height={cell} rx={2} className={r.dark ? "rs-dark" : "rs-off"} aria-hidden="true" />;
        const cls = ["rs-dug", r.hit ? "rs-hit" : "", selected === r.roundId ? "rs-selected" : ""].filter(Boolean).join(" ");
        const winText = r.winningSquare === null ? ", no winning tile" : `, winning tile ${r.winningSquare + 1}`;
        const label = `Round ${r.roundId}: dug ${popcount(r.dugMask)} tiles${winText}${r.hit ? ", your tile came up" : ""}${r.motherlode ? ", Motherlode round" : ""}`;
        return (
          <g
            key={r.roundId}
            role="button"
            tabIndex={0}
            aria-label={label}
            aria-pressed={selected === r.roundId}
            onClick={() => onSelect(r)}
            onFocus={() => onSelect(r)}
            onMouseEnter={() => onSelect(r)}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") onSelect(r);
            }}
          >
            <rect x={x - 1} y={y - 1} width={cell + 2} height={cell + 2} fill="transparent" />
            <rect x={x} y={y} width={cell} height={cell} rx={2} className={cls} />
          </g>
        );
      })}
    </svg>
    </div>
  );
}

/** The phone's morning reveal, on the web: the night replayed, the counts, the price against market, the streak. */
export function HaulView({ haul, simulated, dataset }: { haul: HaulSummary; simulated: boolean; dataset?: DatasetName }) {
  const rounds = useMemo(() => roundViews(haul), [haul]);
  const dug = rounds.filter((r) => r.dug);
  const hits = dug.filter((r) => r.hit);
  const [selected, setSelected] = useState<RoundView | null>(hits[hits.length - 1] ?? dug[dug.length - 1] ?? null);
  const v = verdict(haul);
  const when = haul.mode === "night" ? "last night" : "this shift";
  const nearMiss = motherlodeNearMiss(rounds);
  const sharedMotherlode = hits.some((r) => r.motherlode);
  const shiftLog = simulated ? null : safeExplorerUrl(haul.explorer.shift_log);
  const digLinks = simulated ? [] : haul.explorer.sample_digs.map((u) => safeExplorerUrl(u)).filter((u): u is string => u !== null);
  // The API lists at most 4,096 rounds; a shift left open for days has more (its totals still cover all of it).
  const span = BigInt(haul.end_round) - BigInt(haul.start_round) + 1n;
  const unlisted = span > BigInt(rounds.length) ? span - BigInt(rounds.length) : 0n;

  return (
    <article className="haul" aria-label="Morning haul">
      <header className="haul-head">
        <div className="small muted">
          HEADS DOWN · rig <span className="mono">{shortId(haul.rig)}</span> · shift {String(haul.shift_id)} · {haul.mode.replace("_", "-")}{" "}
          {simulated ? <span className="sim-badge" title="Simulated data">SIM</span> : null}
        </div>
        <h2 className="haul-title">Morning haul</h2>
        <div className="muted">
          {clockUtc(haul.start_ts)} – {clockUtc(haul.end_ts)} UTC · {duration(haul.end_ts - haul.start_ts)} shift · ORE rounds{" "}
          <span className="mono">
            {String(haul.start_round)}–{String(haul.end_round)}
          </span>
        </div>
      </header>

      <section className="card section" aria-label="The shift, round by round">
        <h3>The shift, round by round</h3>
        <div className="legend">
          <span>
            <i className="swatch rs-key rs-off" aria-hidden="true" />
            No lease (not dark)
          </span>
          <span>
            <i className="swatch rs-key rs-dark" aria-hidden="true" />
            Dark, no dig
          </span>
          <span>
            <i className="swatch rs-key rs-dug" aria-hidden="true" />
            Dug
          </span>
          <span>
            <i className="swatch rs-key rs-dug rs-hit" aria-hidden="true" />
            Your tile came up
          </span>
        </div>
        <RoundStrip rounds={rounds} selected={selected?.roundId ?? null} onSelect={setSelected} />
        {unlisted > 0n ? (
          <p className="small muted" role="note">
            This shift stayed open for {formatInt(Number(span))} ORE rounds. The first {formatInt(rounds.length)} are drawn here; the counts and prices
            below cover the whole shift.
          </p>
        ) : null}
        <div className="haul-board-row">
          <Board round={selected} />
          <p className="small muted" style={{ margin: 0 }}>
            {selected ? (
              <>
                Round <span className="mono">{selected.roundId}</span>: the rig dug {popcount(selected.dugMask)} tiles;{" "}
                {selected.winningSquare === null ? "ORE drew no winning tile (every lamport refunded)" : `tile ${selected.winningSquare + 1} won`}
                {selected.winningSquare === null ? "" : selected.split ? " (the +1 ORE split pro rata)" : " (a solo round: at most one miner takes the +1 ORE)"}
                {selected.hit ? ". The rig's tile came up." : "."}
                {selected.motherlode ? " The Motherlode hit this round." : ""}
              </>
            ) : (
              "No dig this shift, so there is no board to replay."
            )}
            <br />
            Ember: where the rig dug. Ring: the round&apos;s winning tile. Star: the rig&apos;s tile came up.
          </p>
        </div>
        <details className="table-view">
          <summary>Show dug rounds as a table</summary>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">Round</th>
                  <th scope="col" className="num">
                    Tiles dug
                  </th>
                  <th scope="col" className="num">
                    Winning tile
                  </th>
                  <th scope="col">Came up</th>
                  <th scope="col">Round type</th>
                </tr>
              </thead>
              <tbody>
                {dug.map((r) => (
                  <tr key={r.roundId}>
                    <td className="mono">{r.roundId}</td>
                    <td className="num">{popcount(r.dugMask)}</td>
                    <td className="num">{r.winningSquare === null ? "none" : r.winningSquare + 1}</td>
                    <td>{r.hit ? "yes ★" : "no"}</td>
                    <td>
                      {r.winningSquare === null ? "refunded" : r.split ? "split" : "solo"}
                      {r.motherlode ? " · Motherlode" : ""}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      </section>

      <div className="grid tiles section">
        <StatTile label="Rounds dark" value={formatInt(Number(haul.dark_rounds))} sub="Covered by a heartbeat lease" simulated={simulated} />
        <StatTile label="Digs" value={formatInt(Number(haul.rounds_dug))} sub="Rounds the rig placed SOL" simulated={simulated} />
        <StatTile label="SOL placed" value={formatSol(String(haul.sol_placed_lamports), 4)} sub="Most of it comes back under ORE's rules" simulated={simulated} />
        <StatTile label="ORE mined" swatch="haul" value={formatOre(haul.ore_mined_atoms, 5)} sub="Unrefined, by ORE's own checkpoint rules" simulated={simulated} />
      </div>

      <section className="card section" aria-label="Price">
        <div className="price-row">
          <div>
            <div className="tile-label">Effective price</div>
            <div className="tile-value">{haul.effective_lamports_per_ore ? `${priceSol(haul.effective_lamports_per_ore)} SOL per ORE` : "—"}</div>
          </div>
          <div>
            <div className="tile-label">Market</div>
            <div className="tile-value">{haul.market_lamports_per_ore ? `${priceSol(haul.market_lamports_per_ore)} SOL per ORE` : "—"}</div>
            <div className="tile-sub">{haul.market_source ? `quote: ${haul.market_source}` : "no quote"}</div>
          </div>
        </div>
        <p className="verdict" role="status">
          {verdictLine(v, when)}
        </p>
        {dataset === "localnet" || dataset === "devnet" ? (
          <p className="small pill-warn" role="note">
            This shift ran on {dataset}, not mainnet. Few miners share the board there, so its price per ORE says nothing about mining on mainnet.
          </p>
        ) : null}
        {sharedMotherlode ? <p className="small">The rig&apos;s tile shared a Motherlode this shift.</p> : null}
        {nearMiss ? (
          <p className="small muted">
            The Motherlode hit the board in round <span className="mono">{nearMiss.roundId}</span>, on a tile the rig did not dig.
          </p>
        ) : null}
        <p className="small muted" style={{ marginBottom: 0 }}>
          Fees: {formatSol(String(haul.fees_lamports), 6)} (the Automation&apos;s per-dig fee). SOL placed is what the Automation put on the
          board; the effective price counts only the SOL that did not come back, plus fees, per ORE mined. ORE mined is unrefined,
          before ORE&apos;s 10% refining fee on claim.
        </p>
      </section>

      <section className="card section" aria-label="Streak">
        <div className="streak">{streakLine(haul.streak_before, haul.streak_after)}</div>
        <div className="small muted">
          Shift ended: {breakReasonText(haul.break_reason)}. First pickup is not on chain; the phone keeps it locally.
        </div>
        <div className="evidence" aria-label="On-chain evidence">
          <span className="muted">Verify:</span>
          {shiftLog ? (
            <a className="mono" href={shiftLog} target="_blank" rel="noopener noreferrer">
              ShiftLog account ↗
            </a>
          ) : (
            <span className="noproof">{simulated ? "ShiftLog (sim)" : "ShiftLog: no explorer link"}</span>
          )}
          {digLinks.map((u, i) => (
            <a key={u} className="mono" href={u} target="_blank" rel="noopener noreferrer">
              dig {i + 1} ↗
            </a>
          ))}
        </div>
        <p className="small muted" style={{ marginBottom: 0 }}>
          Mining is one route to ORE, not income. Some nights buying is cheaper, and this page says so.
        </p>
      </section>
    </article>
  );
}
