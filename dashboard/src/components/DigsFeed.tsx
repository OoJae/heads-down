import Link from "next/link";
import { formatCost, formatSol, formatUtc, shortId, timeAgo } from "@/lib/format";
import type { FeedItem } from "@/lib/types";
import { ExplorerLink } from "./Evidence";

/** Recent digs, newest first. Each row links the dig transaction and the rig account. */
export function DigsFeed({ items, simulated, now }: { items: FeedItem[]; simulated: boolean; now: number }) {
  if (items.length === 0) return <p className="muted">No digs yet.</p>;
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">Rig</th>
            <th scope="col">Tier</th>
            <th scope="col" className="num">
              ORE round
            </th>
            <th scope="col" className="num">
              SOL deployed
            </th>
            <th scope="col" className="num">
              Squares
            </th>
            <th scope="col" className="num">
              Gate cost
            </th>
            <th scope="col">Transaction</th>
          </tr>
        </thead>
        <tbody>
          {items.map((d) => (
            <tr key={`${d.signature}-${d.rig}`}>
              <td title={formatUtc(d.blockTime)}>{timeAgo(d.blockTime, now)}</td>
              <td>
                <ExplorerLink url={d.rigUrl} id={d.rig} simulated={simulated} />{" "}
                <Link className="small" href={`/haul/?rig=${encodeURIComponent(d.rig)}`} title={`Morning haul of rig ${d.rig}`}>
                  haul
                </Link>
              </td>
              <td>
                {d.tier === 1 ? (
                  <span>
                    <i className="swatch sw-seeker" aria-hidden="true" /> Seeker
                  </span>
                ) : d.tier === 0 ? (
                  <span>
                    <i className="swatch sw-guest" aria-hidden="true" /> Guest
                  </span>
                ) : (
                  <span className="muted">—</span>
                )}
              </td>
              <td className="num mono">{d.roundId}</td>
              <td className="num">{formatSol(d.lamports, 4)}</td>
              <td className="num">{d.squares}</td>
              <td className="num" title="Pot-adjusted production cost the on-chain gate compared (lamports per ORE)">
                {formatCost(d.emaEv)}
              </td>
              <td>
                <ExplorerLink url={d.txUrl} id={d.signature} simulated={simulated} />
                {d.authority ? <span className="muted small"> · wallet {shortId(d.authority)}</span> : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
