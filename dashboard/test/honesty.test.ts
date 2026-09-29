// @vitest-environment node
/**
 * Positioning rule (buildplan.md): never say earn, yield, stake or passive income. This scans
 * every source file and the static HTML build (when present).
 */
import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const BANNED = /\b(earn(s|ed|ing)?|yield(s|ed|ing)?|stak(e|es|ed|ing)|passive income|apy|apr)\b/i;
// fileURLToPath, not URL.pathname: the repo path contains a space ("Solana mobile").
const root = fileURLToPath(new URL("..", import.meta.url));

function files(dir: string, exts: string[]): string[] {
  if (!existsSync(dir)) return [];
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...files(p, exts));
    else if (exts.some((e) => p.endsWith(e))) out.push(p);
  }
  return out;
}

describe("honest labelling", () => {
  it("no banned wording in any source file", () => {
    const src = files(join(root, "src"), [".ts", ".tsx", ".css"]);
    expect(src.length).toBeGreaterThan(15); // the scan must actually see the sources
    const offenders = src
      .map((f) => [f, readFileSync(f, "utf8").match(BANNED)?.[0]] as const)
      .filter(([, m]) => m);
    expect(offenders).toEqual([]);
  });

  it("no banned wording in the built pages (if built)", () => {
    const html = files(join(root, "out"), [".html"]);
    if (existsSync(join(root, "out"))) expect(html.length).toBeGreaterThanOrEqual(6);
    const offenders = html
      .map((f) => [f, readFileSync(f, "utf8").replace(/<script[\s\S]*?<\/script>/g, "").match(BANNED)?.[0]] as const)
      .filter(([, m]) => m);
    expect(offenders).toEqual([]);
  });

  it("the check itself catches the banned words", () => {
    for (const s of ["Earn ORE", "real yield", "stake SKR", "passive income", "staking rewards"]) expect(BANNED.test(s)).toBe(true);
    expect(BANNED.test("learn more")).toBe(false);
    expect(BANNED.test("a mistake")).toBe(false);
  });
});
