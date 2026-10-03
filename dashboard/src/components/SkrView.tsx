import { formatInt, formatOre, formatSkr, formatSol } from "@/lib/format";
import type { SkrSummary } from "@/lib/types";
import { StatTile } from "./StatTile";

/**
 * The SKR features, as counts and sums of the program's own events. Nothing here is a price or a
 * return: a bond that comes back is the player's own SKR, and what finishers receive on top was
 * forfeited by the seats that did not finish.
 */
export function SkrView({ data, simulated }: { data: SkrSummary; simulated: boolean }) {
  const { stack, focusBond, gift, bury } = data;
  const quiet = stack.tablesOpened + focusBond.locked + gift.created + bury.lots === 0;
  return (
    <>
      {quiet ? (
        <div className="card state">No Stack table, Focus Bond, gift or Bury lot on this dataset yet.</div>
      ) : null}

      <h2 className="section-title">Stack: phones down together</h2>
      <div className="grid tiles">
        <StatTile label="Tables opened" value={formatInt(stack.tablesOpened)} sub={`${formatInt(stack.tablesSettled)} settled`} simulated={simulated} />
        <StatTile label="Seats taken" value={formatInt(stack.seatsJoined)} sub={`${formatSkr(stack.skrBonded)} bonded in total`} simulated={simulated} />
        <StatTile
          label="Finishers"
          value={formatInt(stack.finishers)}
          sub={`of ${formatInt(stack.seatsSettled)} seats at settled tables`}
          simulated={simulated}
        />
        <StatTile
          label="Rounds counted at tables"
          value={formatInt(stack.checkinsCounted)}
          sub={`${formatInt(stack.checkinsRefused)} check-ins not counted (a gap, a break, or no lease for the round)`}
          simulated={simulated}
        />
        <StatTile
          label="Forfeited SKR to finishers"
          value={formatSkr(stack.skrToFinishers)}
          sub="on top of their own bonds, paid by seats that did not finish"
          simulated={simulated}
        />
        <StatTile label="Forfeited SKR to the Bury lot" value={formatSkr(stack.skrToBury)} sub="sold for ORE, which ORE then buries" simulated={simulated} />
      </div>

      <h2 className="section-title">Focus Bond: SKR locked on a solo shift</h2>
      <div className="grid tiles">
        <StatTile label="Bonds locked" value={formatInt(focusBond.locked)} sub={formatSkr(focusBond.skrLocked)} simulated={simulated} />
        <StatTile label="Returned after a completed shift" value={formatInt(focusBond.released)} sub={formatSkr(focusBond.skrReleased)} simulated={simulated} />
        <StatTile label="Forfeited after a break" value={formatInt(focusBond.forfeited)} sub={`${formatSkr(focusBond.skrForfeited)} to the Bury lot`} simulated={simulated} />
      </div>

      <h2 className="section-title">Gift a Rig</h2>
      <div className="grid tiles">
        <StatTile
          label="Gifts sent"
          value={formatInt(gift.created)}
          sub={`${formatSol(gift.lamportsCreated)}; ${formatInt(gift.createdForSeeker)} addressed to a Seeker`}
          simulated={simulated}
        />
        <StatTile
          label="Gifts claimed"
          value={formatInt(gift.claimed)}
          sub={`${formatSol(gift.lamportsClaimed)}; ${formatInt(gift.claimedBySeeker)} by the holder of the Seeker Genesis Token`}
          simulated={simulated}
        />
        <StatTile label="Gifts returned to the sender" value={formatInt(gift.refunded)} sub={formatSol(gift.lamportsRefunded)} simulated={simulated} />
      </div>

      <h2 className="section-title">Bury auction: forfeited SKR sold for ORE</h2>
      <div className="grid tiles">
        <StatTile
          label="SKR into the lot"
          value={formatSkr(bury.skrIn)}
          sub={`${formatSkr(bury.skrFromStack)} from tables, ${formatSkr(bury.skrFromBonds)} from bonds, in ${formatInt(bury.lots)} deposits`}
          simulated={simulated}
        />
        <StatTile label="SKR sold" value={formatSkr(bury.skrSold)} sub={`${formatInt(bury.sales)} purchases`} simulated={simulated} />
        <StatTile
          label="ORE paid by buyers"
          value={formatOre(bury.orePaid, 6)}
          sub="all of it goes through ORE's own bury instruction"
          swatch="haul"
          simulated={simulated}
        />
        <StatTile
          label="ORE burned"
          value={formatOre(bury.oreBurned, 6)}
          sub={`90% of each payment; the other 10% (${formatOre(bury.oreToStakers, 6)}) ORE's bury passes on to holders who lock ORE`}
          swatch="haul"
          simulated={simulated}
        />
        <StatTile
          label="On offer now"
          value={bury.lotSkr === null ? "—" : formatSkr(bury.lotSkr)}
          sub={
            bury.lastPrice === null
              ? "no sale yet"
              : `last sale at ${formatOre(bury.lastPrice, 8)} per SKR; the price falls until someone buys`
          }
          simulated={simulated}
        />
      </div>
    </>
  );
}
