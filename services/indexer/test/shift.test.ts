/**
 * Leases, dark rounds and streaks: the program's own unit vectors
 * (programs/heads-down/program/src/logic.rs tests `leases_extend_forward_and_count_dark_rounds_and_gaps`,
 * `settling_trims_future_lease_rounds_and_adds_trailing_gaps`, `streak_rules`) run against the
 * indexer's ports, plus the dark-round reconstruction the haul uses.
 */
import { describe, expect, it } from "vitest";
import { LeaseError, grantLease, reconstructDarkRounds, settleShift } from "../src/metrics/lease.ts";
import { INITIAL_STREAK, replayStreaks, unixDay, updateStreak } from "../src/metrics/streak.ts";

describe("grant_lease / settle_shift (logic.rs vectors)", () => {
  it("leases extend forward and count dark rounds and gaps", () => {
    const g = grantLease(0n, 0n, 100n, 100n, 3);
    expect(g).toEqual({ from: 100n, to: 102n, darkAdded: 3n, gapAdded: 0n });
    const g2 = grantLease(g.from, g.to, 100n, 101n, 3);
    expect(g2).toEqual({ from: 101n, to: 103n, darkAdded: 1n, gapAdded: 0n });
    expect(grantLease(g2.from, g2.to, 100n, 99n, 3)).toEqual({ from: 101n, to: 103n, darkAdded: 0n, gapAdded: 0n });
    expect(grantLease(g2.from, g2.to, 100n, 107n, 1)).toEqual({ from: 107n, to: 107n, darkAdded: 1n, gapAdded: 3n });
    expect(grantLease(0n, 0n, 100n, 104n, 1)).toEqual({ from: 104n, to: 104n, darkAdded: 1n, gapAdded: 4n });
    expect(grantLease(0n, 0n, 100n, 98n, 3)).toEqual({ from: 98n, to: 100n, darkAdded: 1n, gapAdded: 0n });
    expect(() => grantLease(0n, 0n, 1n, 5n, 0)).toThrow(LeaseError);
    expect(() => grantLease(0n, 0n, 1n, (1n << 64n) - 1n, 3)).toThrow(/MathOverflow/);
  });

  it("settling trims future lease rounds and adds trailing gaps", () => {
    expect(settleShift(6n, 0n, 105n, 100n, 103n)).toEqual([4n, 0n]);
    expect(settleShift(3n, 1n, 102n, 100n, 110n)).toEqual([3n, 9n]);
    expect(settleShift(0n, 0n, 0n, 100n, 104n)).toEqual([0n, 5n]);
  });
});

describe("dark-round reconstruction", () => {
  it("replays heartbeats in counter order and clips to end_round", () => {
    const r = reconstructDarkRounds({
      shiftStart: 100n,
      endRound: 109n,
      planLease: 3,
      // Landed out of order on purpose: the counter decides the order the program applied them in.
      heartbeats: [
        { counter: 3n, hbRound: 101n, leaseRounds: 3 },
        { counter: 1n, hbRound: 100n, leaseRounds: 3 },
        { counter: 4n, hbRound: 107n, leaseRounds: 3 }, // after a gap: covers 107..109 (110 is past the end)
        { counter: 5n, hbRound: 108n, leaseRounds: 3 },
      ],
    });
    expect(r.rounds).toEqual([100n, 101n, 102n, 103n, 107n, 108n, 109n]);
    expect(r.darkRounds).toBe(7n);
    expect(r.gaps).toBe(3n); // 104, 105, 106
  });

  it("caps each lease at the plan's lease", () => {
    const r = reconstructDarkRounds({ shiftStart: 10n, endRound: 20n, planLease: 1, heartbeats: [{ counter: 1n, hbRound: 10n, leaseRounds: 3 }] });
    expect(r.rounds).toEqual([10n]);
  });

  it("never throws on an entry the program could not have applied (zero lease, overflowing round)", () => {
    const r = reconstructDarkRounds({
      shiftStart: 10n,
      endRound: 20n,
      planLease: 3,
      heartbeats: [
        { counter: 1n, hbRound: 10n, leaseRounds: 0 },
        { counter: 2n, hbRound: (1n << 64n) - 1n, leaseRounds: 3 },
        { counter: 3n, hbRound: 12n, leaseRounds: 2 },
      ],
    });
    expect(r.rounds).toEqual([12n, 13n]);
    expect(r.darkRounds).toBe(2n);
    expect(reconstructDarkRounds({ shiftStart: 10n, endRound: 20n, planLease: 0, heartbeats: [{ counter: 1n, hbRound: 10n, leaseRounds: 3 }] }).rounds).toEqual([]);
  });

  it("a shift with no applied heartbeat has no dark round", () => {
    expect(reconstructDarkRounds({ shiftStart: 10n, endRound: 20n, planLease: 3, heartbeats: [] })).toEqual({ rounds: [], darkRounds: 0n, gaps: 11n });
  });
});

describe("update_streak (logic.rs vectors)", () => {
  const s = (streak: number, freezesLeft: number, lastDay: number) => ({ streak, freezesLeft, lastDay: BigInt(lastDay) });
  const cases: [ReturnType<typeof s>, number, boolean, ReturnType<typeof s>, string][] = [
    [s(0, 0, 0), 20_000, true, s(1, 2, 20_000), "first qualifying shift"],
    [s(1, 2, 20_000), 20_001, true, s(2, 2, 20_001), "next day"],
    [s(2, 2, 20_001), 20_001, true, s(2, 2, 20_001), "same day: unchanged"],
    [s(2, 2, 20_001), 20_003, true, s(3, 1, 20_003), "one missed day, covered by a freeze"],
    [s(3, 2, 20_003), 20_006, true, s(4, 0, 20_006), "two missed days, both freezes"],
    [s(4, 0, 20_006), 20_008, true, s(1, 0, 20_008), "missed more days than freezes: restart"],
    [s(4, 1, 20_006), 20_007, false, s(4, 1, 20_006), "not qualifying: nothing but the refill check"],
    [s(5, 0, 20_009), 20_011, true, s(6, 1, 20_011), "new 30-day period refills to 2 before judging"],
    [s(5, 1, 20_011), 20_010, true, s(5, 1, 20_011), "clock going backwards changes nothing"],
  ];
  for (const [before, today, q, after, why] of cases) {
    it(why, () => expect(updateStreak(before, BigInt(today), q)).toEqual(after));
  }

  it("replays a rig's shifts into before/after per shift, restarting at each registration", () => {
    const day = (d: number) => BigInt(d) * 86_400n + 3_600n;
    const m = replayStreaks(
      [
        { shiftId: 1n, endTs: day(20_000), reason: 0, darkRounds: 10n, order: 10n },
        { shiftId: 2n, endTs: day(20_001), reason: 1, darkRounds: 10n, order: 20n }, // pickup: does not qualify
        { shiftId: 3n, endTs: day(20_002), reason: 0, darkRounds: 10n, order: 30n }, // one missed day, freeze used
        { shiftId: 4n, endTs: day(20_003), reason: 0, darkRounds: 0n, order: 40n }, // no dark round: does not qualify
        { shiftId: 5n, endTs: day(20_010), reason: 0, darkRounds: 1n, order: 60n }, // after re-registration
      ],
      [5n, 50n],
    );
    expect(m.get(1n)).toEqual({ before: 0, after: 1 });
    expect(m.get(2n)).toEqual({ before: 1, after: 1 });
    expect(m.get(3n)).toEqual({ before: 1, after: 2 });
    expect(m.get(4n)).toEqual({ before: 2, after: 2 });
    expect(m.get(5n)).toEqual({ before: 0, after: 1 });
    expect(unixDay(86_399n)).toBe(0n);
    expect(INITIAL_STREAK).toEqual({ streak: 0, freezesLeft: 2, lastDay: 0n });
  });
});
