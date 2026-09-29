import { describe, expect, it } from "vitest";
import { DatasetMismatchError, createClient, parseApiBase, safeExplorerUrl } from "@/lib/api";
import { fixedPoint, formatCompact, formatCost, formatHours, formatOre, formatPct, formatSol, formatTz, shortId, timeAgo } from "@/lib/format";

describe("format", () => {
  it("formats lamports and ORE base units exactly with BigInt", () => {
    expect(formatSol("19731802680", 2)).toBe("19.73 SOL");
    expect(formatSol("1000000000")).toBe("1 SOL");
    expect(formatSol("0")).toBe("0 SOL");
    expect(formatSol("1", 3)).toBe("<0.001 SOL"); // dust is never shown as zero
    expect(formatOre("180710440028", 3)).toBe("1.807 ORE");
    // u64::MAX must not lose precision
    expect(fixedPoint("18446744073709551615", 9, 9)).toBe("18,446,744,073.709551615");
    expect(fixedPoint("12.5", 9, 3)).toBe("—");
    expect(formatCost("541000000")).toBe("0.541 SOL/ORE");
  });

  it("formats percentages, hours, compact numbers, ids and time zones", () => {
    expect(formatPct(0.8167, 0)).toBe("82%");
    expect(formatPct(null)).toBe("—");
    expect(formatPct(Number.NaN)).toBe("—");
    expect(formatHours(6270.355)).toBe("6,270 h");
    expect(formatHours(3.14)).toBe("3.1 h");
    expect(formatCompact(1284)).toBe("1,284");
    expect(formatCompact(12_900)).toBe("12.9K");
    expect(shortId("By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge")).toBe("By3v…Pkge");
    expect(formatTz(60)).toBe("UTC+01:00");
    expect(formatTz(-180)).toBe("UTC−03:00");
    expect(timeAgo(1000, 1030)).toBe("30 s ago");
    expect(timeAgo(0, 8 * 3600)).toBe("8 h ago");
  });
});

describe("API client", () => {
  it("accepts only http(s) bases without credentials", () => {
    expect(parseApiBase("https://api.example.com/")).toBe("https://api.example.com");
    expect(parseApiBase("http://127.0.0.1:8787")).toBe("http://127.0.0.1:8787");
    expect(parseApiBase("javascript:alert(1)")).toBeNull();
    expect(parseApiBase("https://user:pass@api.example.com")).toBeNull();
    expect(parseApiBase("")).toBeNull();
    expect(parseApiBase(undefined)).toBeNull();
  });

  const env = (name: string, extra: Record<string, unknown> = {}) => ({
    dataset: { name, simulated: name === "simulated", programId: "HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p", executorPda: "x", simSeed: null },
    asOf: 1,
    generatedAt: "",
    data: { ok: true },
    ...extra,
  });
  const fakeFetch = (bodies: unknown[]) => {
    let i = 0;
    return (async () => new Response(JSON.stringify(bodies[i++]), { status: 200 })) as unknown as typeof fetch;
  };

  it("pins the first dataset and refuses to mix in another", async () => {
    const c = createClient("https://api.example.com", fakeFetch([env("simulated"), env("simulated"), env("mainnet")]));
    const seen: string[] = [];
    c.subscribe((d) => seen.push(d.name));
    await c.get("/v1/summary");
    await c.get("/v1/cohorts");
    await expect(c.get("/v1/summary")).rejects.toBeInstanceOf(DatasetMismatchError);
    expect(seen).toEqual(["simulated"]);
    expect(c.dataset()?.name).toBe("simulated");
  });

  it("rejects malformed envelopes, including a 'mainnet' that claims to be simulated", async () => {
    const liar = env("mainnet");
    liar.dataset.simulated = true;
    const c = createClient("https://api.example.com", fakeFetch([liar, { data: 1 }]));
    await expect(c.get("/v1/summary")).rejects.toThrow(/unexpected response shape/);
    await expect(c.get("/v1/summary")).rejects.toThrow(/unexpected response shape/);
  });

  it("only requests known API paths", () => {
    const c = createClient("https://api.example.com");
    expect(c.url("/v1/summary")).toBe("https://api.example.com/v1/summary");
    expect(() => c.url("//evil.example/x")).toThrow();
    expect(createClient(null).url("/v1/summary")).toBeNull();
  });

  it("allowlists explorer links", () => {
    const sig = "5uZWBsNQamLCRqmLrCpf5G78U1G3eK2wY8qVaQQBvk1Zk4sjNaz56JDZi4VAoBMSDv6YS5vUcnejYJHsGD24MjfQ";
    expect(safeExplorerUrl(`https://solscan.io/tx/${sig}`)).toBe(`https://solscan.io/tx/${sig}`);
    expect(safeExplorerUrl(`https://solscan.io/account/By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge?cluster=devnet`)).not.toBeNull();
    expect(safeExplorerUrl("javascript:alert(1)")).toBeNull();
    expect(safeExplorerUrl(`https://solscan.io.evil.com/tx/${sig}`)).toBeNull();
    expect(safeExplorerUrl(`https://solscan.io/tx/${sig}"><script>`)).toBeNull();
    expect(safeExplorerUrl(null)).toBeNull();
  });
});
