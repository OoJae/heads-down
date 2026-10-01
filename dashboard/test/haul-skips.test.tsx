/**
 * The Skips view and the per-rig morning haul, against real API responses captured from the
 * indexer's simulated dataset (seed "heads-down-demo-v1"), plus the haul client and the verdict
 * arithmetic shared with the phone's reveal (android HaulMathTest parity cases).
 */
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { HaulView, priceSol } from "@/components/HaulView";
import { SkipHistogram, SkipList } from "@/components/SkipsView";
import { DatasetMismatchError, createClient, getHaul, isHaulSummary } from "@/lib/api";
import { duration, roundViews, streakLine, verdict, verdictLine } from "@/lib/haul";
import type { Envelope, HaulSummary, RigShifts, Skips } from "@/lib/types";
import haulJson from "./fixtures/haul.json";
import shiftsJson from "./fixtures/shifts.json";
import skipsJson from "./fixtures/skips.json";

const haul = haulJson as unknown as HaulSummary;
const shifts = shiftsJson as unknown as Envelope<RigShifts>;
const skips = skipsJson as unknown as Envelope<Skips>;
const SIG = "5uZWBsNQamLCRqmLrCpf5G78U1G3eK2wY8qVaQQBvk1Zk4sjNaz56JDZi4VAoBMSDv6YS5vUcnejYJHsGD24MjfQ";

afterEach(cleanup);

describe("fixtures", () => {
  it("are simulated responses (contract B carries its own flag)", () => {
    expect(haul.simulated).toBe(true);
    expect(isHaulSummary(haul)).toBe(true);
    expect(shifts.dataset.simulated).toBe(true);
    expect(skips.dataset.simulated).toBe(true);
    expect(shifts.data.rig).toBe(haul.rig);
  });
});

describe("SkipHistogram", () => {
  it("draws one bar per reason with its name, plain meaning and count, and selects on click", () => {
    let selected: number | null = null;
    const { rerender } = render(<SkipHistogram histogram={skips.data.histogram} selected={null} onSelect={(c) => (selected = c)} />);
    const bars = screen.getAllByRole("button");
    expect(bars).toHaveLength(skips.data.histogram.length);
    const stale = bars.find((b) => b.getAttribute("aria-label")?.startsWith("StaleHeartbeat"))!;
    expect(stale.getAttribute("aria-label")).toMatch(/replay rejected: heartbeat counter not newer/);
    expect(stale.textContent).toMatch(/StaleHeartbeat/);
    fireEvent.focus(stale);
    expect(screen.getByRole("status").textContent).toMatch(/StaleHeartbeat \(0x00000007, heads_down\): \d[\d,]* skips/);
    fireEvent.click(stale);
    expect(selected).toBe(7);
    rerender(<SkipHistogram histogram={skips.data.histogram} selected={7} onSelect={() => {}} />);
    const pressed = screen.getAllByRole("button").filter((b) => b.getAttribute("aria-pressed") === "true");
    expect(pressed.map((b) => b.getAttribute("aria-label")?.split(":")[0])).toEqual(["StaleHeartbeat"]);
    // The other bars recede (emphasis), never repainted another hue.
    expect(document.querySelectorAll(".hbar-muted")).toHaveLength(skips.data.histogram.length - 1);
  });

  it("has a table view with the hex code and meaning, and an empty state", () => {
    render(<SkipHistogram histogram={skips.data.histogram} selected={null} onSelect={() => {}} />);
    const table = screen.getByRole("table");
    expect(table.textContent).toMatch(/0x00000001.*CostGate.*price gate closed/);
    cleanup();
    render(<SkipHistogram histogram={[]} selected={null} onSelect={() => {}} />);
    expect(screen.getByText(/No rig has been skipped yet/)).toBeTruthy();
  });

  it("labels honestly: a replay and a quiet phone are named as such", () => {
    const h = [
      { code: 7, name: "StaleHeartbeat", range: "heads_down" as const, label: "replay rejected: heartbeat counter not newer", count: 3 },
      { code: 8, name: "LeaseExpired", range: "heads_down" as const, label: "phone went quiet: no heartbeat lease covers this round", count: 9 },
    ];
    render(<SkipHistogram histogram={h} selected={null} onSelect={() => {}} />);
    expect(screen.getByRole("figure").textContent).toMatch(/phone went quiet/);
  });
});

describe("SkipList", () => {
  it("links each rig to its haul page and never links simulated transactions", () => {
    render(<SkipList items={skips.data.items} simulated now={skips.asOf} />);
    const rows = within(screen.getByRole("table")).getAllByRole("row");
    expect(rows).toHaveLength(skips.data.items.length + 1);
    const hrefs = screen.getAllByRole("link").map((a) => a.getAttribute("href") ?? "");
    expect(hrefs.every((h) => /^\/haul\/?\?rig=/.test(h))).toBe(true);
  });
});

describe("HaulView (simulated fixture)", () => {
  it("mirrors the phone's reveal: title, counts, price against market, verdict, streak", () => {
    render(<HaulView haul={haul} simulated />);
    expect(screen.getByRole("heading", { name: "Morning haul" })).toBeTruthy();
    expect(screen.getByRole("region", { name: "Rounds dark" }).textContent).toContain(Number(haul.dark_rounds).toLocaleString("en-US"));
    expect(screen.getByRole("region", { name: "Digs" }).textContent).toContain(String(haul.rounds_dug));
    expect(screen.getByRole("region", { name: "ORE mined" }).textContent).toMatch(/ORE/);
    const v = verdict(haul);
    expect(screen.getByRole("status").textContent).toBe(verdictLine(v, haul.mode === "night" ? "last night" : "this shift"));
    const price = screen.getByRole("region", { name: "Price" }).textContent!;
    expect(price).toMatch(/quote: simulated/);
    // Both prices carry their unit and round like the phone's reveal.
    expect(haul.effective_lamports_per_ore).toBe("1062428395");
    expect(price).toContain("Effective price1.062 SOL per ORE");
    expect(haul.market_lamports_per_ore).toBe("694554353");
    expect(price).toContain("Market0.695 SOL per ORE");
    expect(screen.getByRole("region", { name: "Streak" }).textContent).toContain(streakLine(haul.streak_before, haul.streak_after));
    expect(screen.getAllByText("SIM").length).toBeGreaterThanOrEqual(5);
    // Simulated: no explorer link anywhere.
    expect(screen.queryAllByRole("link")).toHaveLength(0);
    expect(screen.getByText("ShiftLog (sim)")).toBeTruthy();
  });

  it("replays the shift: one button per dug round; the board shows the selected round", () => {
    render(<HaulView haul={haul} simulated />);
    const views = roundViews(haul);
    const dug = views.filter((r) => r.dug);
    const strip = screen.getByRole("group", { name: `${haul.rounds.length} ORE rounds of the shift` });
    const buttons = within(strip).getAllByRole("button");
    expect(buttons).toHaveLength(dug.length);
    // The board starts on the last round whose tile came up.
    const lastHit = dug.filter((r) => r.hit).at(-1)!;
    const board = screen.getByRole("grid");
    expect(board.getAttribute("aria-label")).toBe(`ORE board, round ${lastHit.roundId}`);
    expect(within(board).getAllByRole("gridcell")).toHaveLength(25);
    expect(within(board).getByRole("gridcell", { name: `Tile ${lastHit.winningSquare! + 1}: the rig dug it, and it won` }).textContent).toBe("★");
    // Focus another dug round: the board follows (keyboard reaches what hover does).
    const miss = dug.find((r) => !r.hit && r.winningSquare !== null)!;
    fireEvent.focus(buttons[dug.indexOf(miss)]!);
    expect(screen.getByRole("grid").getAttribute("aria-label")).toBe(`ORE board, round ${miss.roundId}`);
    expect(within(screen.getByRole("grid")).getByRole("gridcell", { name: `Tile ${miss.winningSquare! + 1}: the winning tile` })).toBeTruthy();
    // Every dug round is also in the table view.
    const tables = screen.getAllByRole("table");
    expect(within(tables[0]!).getAllByRole("row")).toHaveLength(dug.length + 1);
  });

  it("links allowlisted explorers on real data and drops anything else", () => {
    const real: HaulSummary = {
      ...haul,
      simulated: false,
      explorer: { shift_log: "https://solscan.io/account/By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge", sample_digs: [`https://solscan.io/tx/${SIG}`, "javascript:alert(1)"] },
    };
    render(<HaulView haul={real} simulated={false} />);
    expect(screen.getAllByRole("link").map((a) => a.getAttribute("href"))).toEqual([real.explorer.shift_log, `https://solscan.io/tx/${SIG}`]);
    expect(screen.queryAllByText("SIM")).toHaveLength(0);
  });

  it("says so when the shift is longer than the rounds the API lists", () => {
    const first = Number(haul.start_round);
    const long: HaulSummary = { ...haul, end_round: first + 9_999, rounds: haul.rounds.slice(0, 40) };
    render(<HaulView haul={long} simulated />);
    expect(screen.getByRole("note").textContent).toBe(
      "This shift stayed open for 10,000 ORE rounds. The first 40 are drawn here; the counts and prices below cover the whole shift.",
    );
    cleanup();
    render(<HaulView haul={haul} simulated />);
    expect(screen.queryByRole("note")).toBeNull();
  });

  it("says that a price from a local fork or devnet is not a mainnet price", () => {
    const real: HaulSummary = { ...haul, simulated: false, explorer: { shift_log: null, sample_digs: [] } };
    render(<HaulView haul={real} simulated={false} dataset="localnet" />);
    expect(screen.getByRole("note").textContent).toBe(
      "This shift ran on localnet, not mainnet. Few miners share the board there, so its price per ORE says nothing about mining on mainnet.",
    );
    cleanup();
    render(<HaulView haul={real} simulated={false} dataset="mainnet" />);
    expect(screen.queryByRole("note")).toBeNull();
  });

  it("says so when nothing was dug", () => {
    const closed: HaulSummary = {
      ...haul,
      rounds: haul.rounds.map((r) => ({ ...r, dug_mask: 0 })),
      rounds_dug: 0,
      sol_placed_lamports: 0,
      ore_mined_atoms: "0",
      effective_lamports_per_ore: null,
    };
    render(<HaulView haul={closed} simulated />);
    expect(screen.getByRole("status").textContent).toBe("The price gate stayed closed, so nothing was placed. Buying was the cheaper route.");
    expect(screen.getByText(/No dig this shift/)).toBeTruthy();
  });
});

describe("haul arithmetic and wording (parity with android HaulMathTest)", () => {
  const base = { ...haul, mode: "night" as const };
  const at = (effective: string | null, market: string | null) => verdict({ ...base, effective_lamports_per_ore: effective, market_lamports_per_ore: market });

  it("verdicts", () => {
    expect(at("680000000", "758000000")).toEqual({ kind: "mining_cheaper", percent: 10 });
    expect(at("1190000000", "758000000")).toEqual({ kind: "buying_cheaper", percent: 57 });
    expect(at("760000000", "758000000")).toEqual({ kind: "about_market" });
    expect(at(null, "758000000")).toEqual({ kind: "nothing_mined" });
    expect(at("680000000", null)).toEqual({ kind: "no_market" });
    expect(verdict({ ...base, mode: "focus_only" })).toEqual({ kind: "focus_only" });
    expect(verdictLine({ kind: "mining_cheaper", percent: 10 }, "last night")).toBe("Mining was the cheaper route last night: 10% below market.");
    expect(verdictLine({ kind: "buying_cheaper", percent: 57 }, "last night")).toBe("Buying was the cheaper route last night: mining cost 57% more than market.");
  });

  it("formats like the reveal", () => {
    expect(duration(7 * 3600 + 40 * 60 + 59)).toBe("7 h 40 m");
    expect(duration(45 * 60)).toBe("45 m");
    expect(streakLine(22, 23)).toBe("Streak 22 → 23");
    expect(streakLine(3, 3)).toBe("Streak holds at 3");
    expect(streakLine(9, 1)).toBe("Streak reset to 1");
    // HaulFormatTest.price vectors: 3 decimals, zeros kept.
    expect(priceSol("680000000")).toBe("0.680");
    expect(priceSol("758000000")).toBe("0.758");
    expect(priceSol("1190000000")).toBe("1.190");
    // Rounded half-even, as BigDecimal.setScale(3, HALF_EVEN).
    expect(priceSol("1027208830")).toBe("1.027");
    expect(priceSol("694554353")).toBe("0.695");
    expect(priceSol("1500000")).toBe("0.002");
    expect(priceSol("2500000")).toBe("0.002");
    expect(priceSol("2500001")).toBe("0.003");
    expect(priceSol("999999999999")).toBe("1000.000");
    // Dust is never shown as zero (the devstack's 168_600 lamports per ORE, for one).
    expect(priceSol("168600")).toBe("<0.001");
    expect(priceSol("500000")).toBe("<0.001");
    expect(priceSol("0")).toBe("0.000");
    expect(priceSol("1.5")).toBe("—");
    // Larger than u64 (a cost divided by a few atoms of ORE): still exact.
    expect(priceSol("100000000000000000000000")).toBe("100000000000000.000");
  });
});

describe("haul client (contract B is not enveloped)", () => {
  const RIG = haul.rig;
  const resp = (status: number, body: unknown, headers: Record<string, string> = {}) =>
    (async () => new Response(JSON.stringify(body), { status, headers })) as unknown as typeof fetch;

  it("returns the haul when the dataset header and the simulated flag agree", async () => {
    const c = createClient("https://api.example.com");
    const r = await getHaul(c, RIG, "latest", resp(200, haul, { "x-headsdown-dataset": "simulated" }));
    expect(r).toEqual({ status: "ok", haul, dataset: "simulated" });
  });

  it("refuses a haul whose header contradicts its flag, a missing header, or a bad shape", async () => {
    const c = createClient("https://api.example.com");
    await expect(getHaul(c, RIG, "latest", resp(200, haul, { "x-headsdown-dataset": "mainnet" }))).rejects.toThrow(/disagree/);
    await expect(getHaul(c, RIG, "latest", resp(200, haul))).rejects.toThrow(/dataset header/);
    await expect(getHaul(c, RIG, "latest", resp(200, { ...haul, rounds: [{ round_id: 1, dark: true, dug_mask: 1 << 25 }] }, { "x-headsdown-dataset": "simulated" }))).rejects.toThrow(/shape/);
    expect(isHaulSummary({ ...haul, ore_mined_atoms: 5 })).toBe(false);
    expect(isHaulSummary({ ...haul, first_pickup_ts: 1 })).toBe(false);
  });

  it("refuses to mix datasets with the pinned one", async () => {
    const env = { dataset: { name: "mainnet", simulated: false, programId: "x", executorPda: "y", simSeed: null }, asOf: 1, generatedAt: "", data: {} };
    const c = createClient("https://api.example.com", resp(200, env));
    await c.get("/v1/health");
    await expect(getHaul(c, RIG, "latest", resp(200, haul, { "x-headsdown-dataset": "simulated" }))).rejects.toBeInstanceOf(DatasetMismatchError);
  });

  it("turns 404 into an honest message, with the retry hint when the haul is not final", async () => {
    const c = createClient("https://api.example.com");
    const r = await getHaul(c, RIG, "7", resp(404, { error: "shift 7 ended but its haul is not final yet" }, { "retry-after": "30" }));
    expect(r).toEqual({ status: "none", message: "shift 7 ended but its haul is not final yet", retryAfterS: 30 });
    await expect(getHaul(c, "not a rig", "latest", resp(200, haul))).rejects.toThrow(/rig/);
    await expect(getHaul(c, RIG, "../x", resp(200, haul))).rejects.toThrow(/shift/);
  });
});
