import Link from "next/link";

export const metadata = { title: "Method · Heads Down traction" };

const PROGRAM = "HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p";
const EXECUTOR = "By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge";

export default function MethodPage() {
  return (
    <>
      <h1>How these numbers are made</h1>
      <p className="lede">
        Nothing on this site comes from our own database of claims. The indexer reads Solana transactions and accounts,
        and anyone can recompute every figure from the same data. Where a feature has not shipped, the site says{" "}
        <em>not shipped</em>. It never shows a zero in its place.
      </p>

      <section className="card">
        <h2>Sources</h2>
        <dl className="defs">
          <dt>heads_down program events</dt>
          <dd>
            <span className="mono">RigDug</span>, <span className="mono">RigSkipped</span>, <span className="mono">ShiftArmed</span>,{" "}
            <span className="mono">ShiftEnded</span> and <span className="mono">SeekerVerified</span>, logged by program{" "}
            <span className="mono">{PROGRAM}</span>. They are counted only when that program wrote them, only from successful
            transactions, and only at finalized commitment.
          </dd>
          <dt>ORE&apos;s own DeployEvents</dt>
          <dd>
            Every Heads Down dig makes ORE emit a DeployEvent whose <span className="mono">signer</span> is the Heads Down
            Executor PDA <span className="mono">{EXECUTOR}</span> (seeds <span className="mono">[&quot;executor&quot;]</span>).
            Only the heads_down program can sign for it, so ORE can measure Heads Down usage from its own logs.
          </dd>
          <dt>Accounts</dt>
          <dd>
            Rig, SeekerSeat and ShiftLog accounts. Each one&apos;s address is re-derived from its contents (canonical bump) and
            its owner is checked, so no other account can pass as a rig. Seeker tier means the program itself verified a
            Seeker Genesis Token in the same transaction.
          </dd>
          <dt>ORE rounds</dt>
          <dd>
            ORE&apos;s ResetEvent for each round, which carries <span className="mono">total_miners</span>, comes from
            api.ore.com. It is stored with its reset transaction signature, and a sample is re-read from the chain on every
            poll.
          </dd>
        </dl>
      </section>

      <section className="card section">
        <h2>Definitions</h2>
        <dl className="defs">
          <dt>Night</dt>
          <dd>Noon to noon in one fixed time zone (WAT by default). A rig&apos;s own time zone is never collected.</dd>
          <dt>Active rig</dt>
          <dd>Armed a shift, dug, or ended a shift with at least one dark round that night.</dd>
          <dt>Retention D1 / D7 / D14</dt>
          <dd>
            A cohort is the night of a rig&apos;s first armed shift. Dk is the share of the cohort active exactly k nights later.
            A cell appears only once that night is complete.
          </dd>
          <dt>Dark hours</dt>
          <dd>Heartbeat-leased rounds from ShiftEnded events × the measured median ORE round length.</dd>
          <dt>SOL deployed</dt>
          <dd>SOL placed into ORE squares by digs, cross-checked against ORE&apos;s DeployEvents. It stays in ORE&apos;s custody and returns to the rig owner under ORE&apos;s rules.</dd>
          <dt>ORE mined</dt>
          <dd>
            ORE credited by ORE&apos;s checkpoint rules for each Heads Down deploy, from that round&apos;s ResetEvent: a pro-rata
            share on split rounds, all of it on a solo round won, plus any Motherlode share. It is unrefined, before
            ORE&apos;s 10% refining fee on claim.
          </dd>
          <dt>ORE bought / buried</dt>
          <dd>Not shipped yet (clock-out buy leg, Bury auction). Shown as placeholders.</dd>
          <dt>Share of ORE miners</dt>
          <dd>Per round: distinct Heads Down wallets that deployed ÷ ORE&apos;s total miners for that round.</dd>
          <dt>Gate-open rate</dt>
          <dd>
            Digs ÷ (digs + on-chain cost-gate refusals). The crank only submits when it expects the gate to open, so this
            rate runs high. It is shown next to the share of dark rounds that were dug.
          </dd>
        </dl>
      </section>

      <section className="card section">
        <h2>What is not shown, and why</h2>
        <ul style={{ margin: 0, paddingLeft: 18, color: "var(--text-2)" }}>
          <li>
            <strong>Rigs by region.</strong> The app collects no location, and no opt-in region data exists yet, so there is
            no region view. Milestone time windows are time zones, not places.
          </li>
          <li>
            <strong>Returns.</strong> Heads Down is a way to accumulate ORE by the cheaper route when the on-chain gate says
            mining costs less than buying. It is not a return on capital, and this site never presents it as one.
          </li>
        </ul>
      </section>

      <section className="card section">
        <h2>Simulated data</h2>
        <p style={{ margin: 0, color: "var(--text-2)" }}>
          Until the program is deployed, a demo can run against the indexer&apos;s deterministic simulator. Its API marks every
          response <span className="mono">simulated: true</span>, and this site then shows a striped SIMULATED banner and a SIM
          badge on every tile, and links no explorer. A site build pinned to one dataset refuses responses from any other.
          The API reference is in the footer. <Link href="/">Back to the overview</Link>.
        </p>
      </section>
    </>
  );
}
