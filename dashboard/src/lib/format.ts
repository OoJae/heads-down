/** Formatting. u64 strings are handled with BigInt so large amounts never lose precision. */

const intFmt = new Intl.NumberFormat("en-US");

function toBig(v: string): bigint | null {
  return /^\d{1,20}$/.test(v) ? BigInt(v) : null;
}

/** Fixed-point decimal of `v / 10^decimals`, trimmed to `maxFrac` fraction digits (truncated, never rounded up). */
export function fixedPoint(v: string, decimals: number, maxFrac: number): string {
  const b = toBig(v);
  if (b === null) return "—";
  const unit = 10n ** BigInt(decimals);
  const whole = b / unit;
  const frac = (b % unit).toString().padStart(decimals, "0").slice(0, maxFrac).replace(/0+$/, "");
  // Non-zero dust below the display precision is shown as "<0.001", never as "0".
  if (whole === 0n && frac === "" && b > 0n) return `<0.${"0".repeat(maxFrac - 1)}1`;
  const w = intFmt.format(whole);
  return frac ? `${w}.${frac}` : w;
}

export function formatSol(lamports: string, maxFrac = 3): string {
  return `${fixedPoint(lamports, 9, maxFrac)} SOL`;
}

export function formatOre(baseUnits: string, maxFrac = 4): string {
  return `${fixedPoint(baseUnits, 11, maxFrac)} ORE`;
}

/** SKR base units (6 decimals) → "1,250.5 SKR". */
export function formatSkr(baseUnits: string, maxFrac = 2): string {
  return `${fixedPoint(baseUnits, 6, maxFrac)} SKR`;
}

export function formatInt(n: number | null | undefined): string {
  return n === null || n === undefined || !Number.isFinite(n) ? "—" : intFmt.format(Math.round(n));
}

export function formatCompact(n: number): string {
  if (!Number.isFinite(n)) return "—";
  if (Math.abs(n) < 10_000) return intFmt.format(Math.round(n));
  return new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 1 }).format(n);
}

export function formatPct(x: number | null | undefined, digits = 1): string {
  if (x === null || x === undefined || !Number.isFinite(x)) return "—";
  return `${(x * 100).toFixed(digits)}%`;
}

export function formatHours(h: number): string {
  if (!Number.isFinite(h)) return "—";
  return h >= 100 ? `${formatInt(h)} h` : `${h.toFixed(1)} h`;
}

export function shortId(id: string, head = 4, tail = 4): string {
  return id.length <= head + tail + 1 ? id : `${id.slice(0, head)}…${id.slice(-tail)}`;
}

export function formatTz(minutes: number): string {
  const sign = minutes < 0 ? "−" : "+";
  const m = Math.abs(minutes);
  return `UTC${sign}${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
}

export function formatUtc(ts: number | null): string {
  if (ts === null) return "—";
  return new Date(ts * 1000).toISOString().replace("T", " ").slice(0, 16) + " UTC";
}

export function timeAgo(ts: number | null, now: number): string {
  if (ts === null) return "—";
  const s = Math.max(0, now - ts);
  if (s < 90) return `${Math.round(s)} s ago`;
  if (s < 5400) return `${Math.round(s / 60)} min ago`;
  if (s < 172_800) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86_400)} d ago`;
}

/** Lamports per ORE → "0.541 SOL/ORE". */
export function formatCost(lamportsPerOre: string): string {
  return `${fixedPoint(lamportsPerOre, 9, 3)} SOL/ORE`;
}
