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
            All ten events of interface v1.1: <span className="mono">RigRegistered</span>, <span className="mono">RigClosed</span>,{" "}
            <span className="mono">SeekerVerified</span>, <span className="mono">ShiftArmed</span>,{" "}
            <span className="mono">HeartbeatsRecorded</span>, <span className="mono">RigDug</span>,{" "}
            <span className="mono">RigSkipped</span>, <span className="mono">ShiftBroken</span>,{" "}
            <span className="mono">ShiftEnded</span> and <span className="mono">ShiftEndedV2</span>, logged by program{" "}
            <span className="mono">{PROGRAM}</span>. They are counted only when that program wrote them, only from successful
            transactions, and only at finalized commitment. Ending a shift logs both ShiftEnded and ShiftEndedV2; the shift
            is counted once.
          </dd>
          <dt>heads_down instruction data</dt>
          <dd>
            The heartbeat entries inside <span className="mono">dig</span> and <span className="mono">record_heartbeats</span>{" "}
            instructions say which ORE round the phone signed for and how many rounds of lease it asked for. Replaying the
            program&apos;s own lease rules over them tells which rounds were dark, and the result must equal the count the
            program logged when the shift ended.
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
            ORE&apos;s ResetEvent for each round (winning square, top miner, Motherlode, <span className="mono">total_miners</span>),
            from api.ore.com or found on chain, stored with its reset transaction signature; a sample is re-read from the
            chain on every poll. Also the ORE Round account of every round Heads Down dug, read after the round was reset:
            it is the only place ORE keeps each square&apos;s total, which its own arithmetic divides by.
          </dd>
          <dt>Market price (morning haul only)</dt>
          <dd>
            The one figure that is not on chain: a quote for ORE in SOL from a public price API. It is always shown with the
            name of its source, and left empty when no source answers.
          </dd>
        </dl>
      </section>

      <section className="card section">
        <h2>Definitions</h2>
        <dl className="defs">
          <dt>Rigs</dt>
          <dd>
            Rigs registered minus rigs closed, from RigRegistered and RigClosed events. The count is cross-checked against
            the Rig accounts that exist now, and the overview says whether the two agree.
          </dd>
          <dt>Night</dt>
          <dd>Noon to noon in one fixed time zone (WAT by default). A rig&apos;s own time zone is never collected.</dd>
          <dt>Active rig</dt>
          <dd>Armed a shift, dug, or ended a shift with at least one dark round that night.</dd>
          <dt>Retention D1 / D7 / D14</dt>
          <dd>
            A cohort is the night of a rig&apos;s first armed shift. Dk is the share of the cohort active exactly k nights later.
            A cell appears only once that night is complete.
          </dd>
          <dt>Dark round</dt>
          <dd>
            An ORE round covered by a heartbeat lease: the phone, face-down, signed a fresh heartbeat with its Keystore key,
            and the program accepted it on chain for that round and at most two more. A round with no lease is not dark,
            whatever the phone was doing.
          </dd>
          <dt>Dark hours</dt>
          <dd>Dark rounds from ShiftEnded events × the measured median ORE round length.</dd>
          <dt>SOL deployed</dt>
          <dd>SOL placed into ORE squares by digs, cross-checked against ORE&apos;s DeployEvents. It stays in ORE&apos;s custody and returns to the rig owner under ORE&apos;s rules.</dd>
          <dt>ORE mined</dt>
          <dd>
            ORE credited by ORE&apos;s checkpoint rules for each Heads Down deploy, from that round&apos;s outcome: a pro-rata
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
        <h2>Skips</h2>
        <p style={{ marginTop: 0, color: "var(--text-2)" }}>
          When the program declines to dig for a rig it logs a RigSkipped event with a precise code, and the transaction
          still succeeds for the other rigs in it. A skip places no SOL on any square. The{" "}
          <Link href="/skips">Skips</Link> page counts them by code and words each one as what happened on chain:
        </p>
        <dl className="defs">
          <dt>CostGate</dt>
          <dd>The price gate was closed: mining cost more than the rig&apos;s plan allows, so the program did not dig.</dd>
          <dt>StaleHeartbeat</dt>
          <dd>A replay was rejected: the heartbeat&apos;s counter was not newer than the last one the program accepted.</dd>
          <dt>LeaseExpired</dt>
          <dd>The phone went quiet: no heartbeat lease covers this round, so the program refused to dig.</dd>
          <dt>RigNotArmed</dt>
          <dd>No active shift: the rig is idle, broken, or cooling after a pickup without a fresh heartbeat.</dd>
          <dt>Signature checks</dt>
          <dd>
            Codes in the <span className="mono">0x2560_00xx</span> range come from the P-256 heartbeat check (wrong key,
            wrong message, malformed signature) and codes in <span className="mono">0x5347_00xx</span> from the Seeker
            Genesis Token check. They are shown with their own names, never folded into a generic error.
          </dd>
        </dl>
      </section>

      <section className="card section">
        <h2>The morning haul</h2>
        <p style={{ marginTop: 0, color: "var(--text-2)" }}>
          The <Link href="/haul">haul</Link> of one finished shift is the same summary the phone shows at clock-out. It is
          served only once ORE has reset every round the shift dug, so its on-chain figures do not change afterwards.
        </p>
        <dl className="defs">
          <dt>Rounds</dt>
          <dd>Every ORE round from the shift&apos;s first to its last: whether it was dark, which squares were dug, and which square ORE drew.</dd>
          <dt>SOL placed, fees</dt>
          <dd>
            SOL placed is the sum of the digs&apos; RigDug amounts. Fees are what the shift spent beyond that: the per-dig
            Automation fee, which goes to the Heads Down Executor and reimburses the crank that sent the dig. The
            wallet&apos;s own transaction fees for clocking in and out are not included.
          </dd>
          <dt>Effective price</dt>
          <dd>
            (SOL placed − SOL that ORE returns + fees) ÷ ORE mined, for this shift only. Empty when the shift mined no
            ORE. SOL returned follows ORE&apos;s per-square fee formula and is exact when the Round account was read.
          </dd>
          <dt>Market price</dt>
          <dd>The labelled quote described under Sources, for comparison. Mining can cost more or less than buying; the haul shows which it was.</dd>
          <dt>Streak</dt>
          <dd>
            The program&apos;s own streak rule replayed over the rig&apos;s ended shifts: a shift counts when it completed with
            at least one dark round.
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
          <li>
            <strong>First pickup time.</strong> The chain cannot see when a phone was picked up after a shift ended, so the
            haul leaves it empty. Only the phone knows.
          </li>
        </ul>
      </section>

      <section className="card section">
        <h2>Simulated data</h2>
        <p style={{ margin: 0, color: "var(--text-2)" }}>
          A demo can run against the indexer&apos;s deterministic simulator instead of the chain. Its API then marks every
          response <span className="mono">simulated: true</span>, and this site then shows a striped SIMULATED banner and a SIM
          badge on every tile, and links no explorer. A site build pinned to one dataset refuses responses from any other.
          The API reference is in the footer. <Link href="/">Back to the overview</Link>.
        </p>
      </section>
    </>
  );
}
