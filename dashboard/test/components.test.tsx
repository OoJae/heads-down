/**
 * Component tests against real API responses captured from the indexer's simulated dataset
 * (test/fixtures/*.json, `pnpm demo` seed "heads-down-demo-v1").
 */
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CohortTable } from "@/components/CohortTable";
import { ColumnChart } from "@/components/ColumnChart";
import { DatasetBannerView } from "@/components/DatasetBanner";
import { DigsFeed } from "@/components/DigsFeed";
import { EvidenceLinks } from "@/components/Evidence";
import { MilestoneList } from "@/components/MilestoneList";
import { OverviewView } from "@/components/Overview";
import { ShareView } from "@/components/ShareView";
import { StatTile } from "@/components/StatTile";
import type { CohortReport, Envelope, FeedItem, Milestones, ShareByHour, Summary } from "@/lib/types";
import summaryJson from "./fixtures/summary.json";
import cohortsJson from "./fixtures/cohorts.json";
import shareJson from "./fixtures/share.json";
import digsJson from "./fixtures/digs.json";
import milestonesJson from "./fixtures/milestones.json";
import skrJson from "./fixtures/skr.json";
import { SkrView } from "@/components/SkrView";
import type { SkrSummary } from "@/lib/types";

const summary = summaryJson as unknown as Envelope<Summary>;
const cohorts = cohortsJson as unknown as Envelope<CohortReport>;
const share = shareJson as unknown as Envelope<ShareByHour>;
const digs = digsJson as unknown as Envelope<FeedItem[]>;
const milestones = milestonesJson as unknown as Envelope<Milestones>;
const skr = skrJson as unknown as Envelope<SkrSummary>;

const SIG = "5uZWBsNQamLCRqmLrCpf5G78U1G3eK2wY8qVaQQBvk1Zk4sjNaz56JDZi4VAoBMSDv6YS5vUcnejYJHsGD24MjfQ";

afterEach(cleanup);

describe("fixtures", () => {
  it("are simulated responses (so tests never pass real-looking data off as real)", () => {
    for (const f of [summary, cohorts, share, digs, milestones, skr]) {
      expect(f.dataset.name).toBe("simulated");
      expect(f.dataset.simulated).toBe(true);
    }
  });
});

describe("DatasetBanner", () => {
  it("shows an unmissable SIMULATED banner with the seed", () => {
    render(<DatasetBannerView dataset={summary.dataset} />);
    const banner = screen.getByRole("status");
    expect(banner.textContent).toMatch(/SIMULATED DATA/);
    expect(banner.textContent).toMatch(/heads-down-demo-v1/);
    expect(banner.textContent).toMatch(/none of it is traction/i);
  });

  it("shows a quiet on-chain chip for real data, and nothing before the first response", () => {
    const { container, rerender } = render(<DatasetBannerView dataset={{ ...summary.dataset, name: "mainnet", simulated: false, simSeed: null }} />);
    expect(container.textContent).toMatch(/On-chain data · Solana mainnet/);
    expect(container.textContent).not.toMatch(/SIMULATED/);
    rerender(<DatasetBannerView dataset={null} />);
    expect(container.textContent).toBe("");
  });
});

describe("StatTile and evidence", () => {
  it("badges simulated tiles and never links simulated ids", () => {
    render(<StatTile label="Rigs" value="120" simulated evidence={[{ label: "Rig", kind: "account", id: "Hg7LuH69CGkQ8Ah3hzTD98MNQ1FrdjWeCbzeT1ThL1yb", url: "https://solscan.io/account/Hg7LuH69CGkQ8Ah3hzTD98MNQ1FrdjWeCbzeT1ThL1yb" }]} />);
    const tile = screen.getByRole("region", { name: "Rigs" });
    expect(within(tile).getByText("SIM")).toBeTruthy();
    expect(within(tile).queryAllByRole("link")).toHaveLength(0);
    expect(tile.textContent).toMatch(/\(sim\)/);
  });

  it("links real evidence to the allowlisted explorer, with safe rel", () => {
    render(<EvidenceLinks simulated={false} items={[{ label: "dig", kind: "tx", id: SIG, url: `https://solscan.io/tx/${SIG}` }]} />);
    const a = screen.getByRole("link");
    expect(a.getAttribute("href")).toBe(`https://solscan.io/tx/${SIG}`);
    expect(a.getAttribute("rel")).toBe("noopener noreferrer");
  });

  it("drops a non-allowlisted URL even on real data", () => {
    render(<EvidenceLinks simulated={false} items={[{ label: "dig", kind: "tx", id: SIG, url: "javascript:alert(1)" }]} />);
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("shows a not-shipped placeholder instead of a zero", () => {
    render(<StatTile label="ORE bought" value={null} simulated={false} notShipped="Clock-out buy leg not shipped yet." />);
    const tile = screen.getByRole("region", { name: "ORE bought" });
    expect(tile.textContent).toMatch(/Not shipped/);
    expect(tile.textContent).not.toMatch(/\b0\b/);
  });
});

describe("OverviewView", () => {
  it("renders every headline tile from a real API response", () => {
    render(<OverviewView s={summary.data} simulated />);
    for (const label of ["Rigs", "Seeker-verified rigs", "Guest rigs", "Nightly active rigs", "Dark hours", "Rounds dug", "SOL deployed into ORE", "ORE mined", "ORE bought", "ORE buried", "Gate-open rate"]) {
      expect(screen.getByRole("region", { name: label })).toBeTruthy();
    }
    expect(screen.getByRole("region", { name: "Rigs" }).textContent).toContain(String(summary.data.rigs.total));
    expect(screen.getByRole("region", { name: "SOL deployed into ORE" }).textContent).toMatch(/16\.58 SOL/);
    // Rigs are counted from RigRegistered / RigClosed and cross-checked against the Rig accounts.
    expect(screen.getByRole("region", { name: "Rigs" }).textContent).toMatch(/Registered minus closed \(120 ever registered\) · Rig accounts agree ✓/);
    expect(screen.getByRole("region", { name: "Integrity checks" }).textContent).toMatch(/RigRegistered\/RigClosed events vs Rig accounts.*agree ✓/);
    expect(screen.getByRole("region", { name: "Integrity checks" }).textContent).toMatch(/StaleHeartbeat · replay rejected: heartbeat counter not newer/);
    expect(screen.getByRole("region", { name: "Integrity checks" }).textContent).toMatch(/all clear/);
    expect(screen.getAllByText("SIM").length).toBeGreaterThanOrEqual(11);
  });

  it("flags a broken integrity check", () => {
    const bad = { ...summary.data, consistency: { ...summary.data.consistency, lamportsMismatch: 3 } };
    render(<OverviewView s={bad} simulated />);
    expect(screen.getByRole("region", { name: "Integrity checks" }).textContent).toMatch(/needs attention/);
  });
});

describe("ColumnChart", () => {
  const cols = [
    { key: "a", label: "00", segments: [{ value: 0.02, series: "guest" as const }], detail: "2.0% mean" },
    { key: "b", label: "01", segments: null, detail: "no ORE rounds" },
    { key: "c", label: "02", segments: [{ value: 0.01, series: "context" as const }], detail: "1.0% mean" },
  ];

  it("exposes one keyboard-focusable, labelled mark per column and a table view", () => {
    render(<ColumnChart title="Share" columns={cols} formatTick={(v) => `${v}`} tableHeaders={["Hour", "Share"]} />);
    const marks = screen.getAllByRole("img");
    expect(marks).toHaveLength(3);
    expect(marks[0]!.getAttribute("aria-label")).toBe("00: 2.0% mean");
    expect(marks[0]!.getAttribute("tabindex")).toBe("0");
    const table = screen.getByRole("table");
    expect(within(table).getAllByRole("row")).toHaveLength(4);
  });

  it("shows a tooltip on keyboard focus", () => {
    render(<ColumnChart title="Share" columns={cols} formatTick={(v) => `${v}`} tableHeaders={["Hour", "Share"]} />);
    fireEvent.focus(screen.getAllByRole("img")[2]!);
    expect(screen.getByRole("status").textContent).toMatch(/02.*1\.0% mean/);
  });

  it("draws bars no wider than 24px with rounded tops, and an empty state", () => {
    const { container } = render(<ColumnChart title="x" columns={cols} formatTick={(v) => `${v}`} tableHeaders={["a", "b"]} />);
    const paths = container.querySelectorAll("path.mark");
    expect(paths).toHaveLength(2);
    for (const p of paths) {
      const xs = [...(p.getAttribute("d") ?? "").matchAll(/[MH]([\d.]+)/g)].map((m) => Number(m[1]));
      expect(Math.max(...xs) - Math.min(...xs)).toBeLessThanOrEqual(24);
      expect(p.getAttribute("d")).toMatch(/Q/); // rounded data end
    }
    cleanup();
    render(<ColumnChart title="x" columns={cols.map((c) => ({ ...c, segments: null }))} formatTick={(v) => `${v}`} tableHeaders={["a", "b"]} />);
    expect(screen.getByText(/No data in this window yet/)).toBeTruthy();
  });

  it("shows a legend only for two or more series", () => {
    const { container, rerender } = render(<ColumnChart title="x" columns={cols} formatTick={String} tableHeaders={["a", "b"]} legend={[{ series: "guest", label: "G" }]} />);
    expect(container.querySelector(".legend")).toBeNull();
    rerender(<ColumnChart title="x" columns={cols} formatTick={String} tableHeaders={["a", "b"]} legend={[{ series: "guest", label: "G" }, { series: "seeker", label: "S" }]} />);
    expect(container.querySelector(".legend")?.textContent).toBe("GS");
  });
});

describe("CohortTable", () => {
  it("renders cohorts, percentages and 'not yet' for immature cells", () => {
    render(<CohortTable report={cohorts.data} />);
    const table = screen.getByRole("table");
    const rows = within(table).getAllByRole("row");
    expect(rows.length).toBe(cohorts.data.cohorts.length + 2); // header + cohorts + footer
    expect(table.textContent).toMatch(/not yet/);
    const first = cohorts.data.cohorts[0]!;
    expect(within(rows[1]!).getByRole("rowheader").textContent).toBe(first.cohort);
    expect(screen.getAllByText(/^D(1|7|14)$/)).toHaveLength(3);
  });

  it("explains an empty report", () => {
    render(<CohortTable report={{ lastCompleteNight: null, cohorts: [], average: [] }} />);
    expect(screen.getByText(/No cohorts yet/)).toBeTruthy();
  });
});

describe("ShareView", () => {
  it("renders 24 hourly marks and the verification table", () => {
    render(<ShareView data={share.data} simulated />);
    expect(screen.getAllByRole("img")).toHaveLength(24);
    expect(screen.getByRole("region", { name: "Peak rounds" }).textContent).toMatch(/\(sim\)/);
    expect(screen.queryAllByRole("link")).toHaveLength(0);
  });
});

describe("DigsFeed", () => {
  it("lists digs with tier and no explorer links for simulated data (only links to each rig's haul page)", () => {
    render(<DigsFeed items={digs.data} simulated now={digs.asOf} />);
    const rows = within(screen.getByRole("table")).getAllByRole("row");
    expect(rows).toHaveLength(digs.data.length + 1);
    const hrefs = screen.queryAllByRole("link").map((a) => a.getAttribute("href") ?? "");
    expect(hrefs).toHaveLength(digs.data.length);
    // next/link drops the trailing slash outside a build (trailingSlash applies in the static export).
    expect(hrefs.every((h) => /^\/haul\/?\?rig=[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(h))).toBe(true);
  });

  it("links the transaction and the rig on real data", () => {
    const item: FeedItem = { ...digs.data[0]!, signature: SIG, txUrl: `https://solscan.io/tx/${SIG}`, rig: "By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge", rigUrl: "https://solscan.io/account/By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge" };
    render(<DigsFeed items={[item]} simulated={false} now={digs.asOf} />);
    const hrefs = screen.getAllByRole("link").map((a) => (a.getAttribute("href") ?? "").replace("/haul/?", "/haul?"));
    expect(hrefs).toEqual([item.rigUrl, `/haul?rig=${item.rig}`, item.txUrl]);
  });
});

describe("MilestoneList", () => {
  it("renders meters with accessible values and manual items", () => {
    render(<MilestoneList data={milestones.data} />);
    const meters = screen.getAllByRole("meter");
    expect(meters.length).toBe(milestones.data.milestones.reduce((n, m) => n + m.metrics.length, 0));
    expect(meters[0]!.getAttribute("aria-valuetext")).toMatch(/of 250/);
    expect(screen.getByRole("region", { name: "M3 Durability" }).textContent).toMatch(/Immutable v1/);
  });
});

describe("SkrView", () => {
  it("shows the SKR totals as amounts with their units, and where forfeited SKR went", () => {
    render(<SkrView data={skr.data} simulated />);
    const tile = (label: string) => within(screen.getByRole("region", { name: label }));
    tile("Tables opened").getByText("3");
    tile("Seats taken").getByText("2,200 SKR bonded in total");
    tile("Finishers").getByText("of 8 seats at settled tables");
    tile("Forfeited SKR to finishers").getByText("480 SKR");
    tile("Forfeited SKR to the Bury lot").getByText("120 SKR");
    tile("Bonds locked").getByText("3,500 SKR");
    tile("Gifts sent").getByText("0.08 SOL; 1 addressed to a Seeker");
    tile("SKR into the lot").getByText("620 SKR");
    // 0.1 ORE paid: 0.09 burned, 0.01 passed on by ORE's bury. Never "all burned".
    tile("ORE paid by buyers").getByText("0.1 ORE");
    tile("ORE burned").getByText("0.09 ORE");
    tile("ORE burned").getByText(/the other 10% \(0\.01 ORE\)/);
    tile("On offer now").getByText("220 SKR");
    // Simulated numbers are labelled on every tile.
    expect(screen.getAllByText("SIM").length).toBeGreaterThan(10);
  });

  it("says so when nothing has happened yet, instead of a wall of zeros with no explanation", () => {
    const zero = JSON.parse(JSON.stringify(skr.data)) as SkrSummary;
    for (const group of [zero.stack, zero.focusBond, zero.gift, zero.bury] as Record<string, unknown>[]) {
      for (const k of Object.keys(group)) group[k] = typeof group[k] === "number" ? 0 : "0";
    }
    zero.bury.lotSkr = null;
    zero.bury.lastPrice = null;
    render(<SkrView data={zero} simulated={false} />);
    screen.getByText("No Stack table, Focus Bond, gift or Bury lot on this dataset yet.");
    within(screen.getByRole("region", { name: "On offer now" })).getByText("no sale yet");
    expect(screen.queryByText("SIM")).toBeNull();
  });
});
