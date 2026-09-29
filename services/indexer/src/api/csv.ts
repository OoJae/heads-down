/**
 * RFC 4180 CSV with spreadsheet formula-injection protection: a text cell beginning with
 * = + - @ TAB or CR is prefixed with a single quote so Excel/Sheets never evaluate it.
 * (Every value we export is a number, an ISO date or base58, but the writer does not rely
 * on that.)
 */
export type Cell = string | number | bigint | boolean | null | undefined;

const DANGEROUS = /^[=+\-@\t\r]/;

export function csvCell(v: Cell): string {
  if (v === null || v === undefined) return "";
  if (typeof v === "number") {
    if (!Number.isFinite(v)) return "";
    return String(v);
  }
  if (typeof v === "bigint" || typeof v === "boolean") return String(v);
  let s = v;
  if (DANGEROUS.test(s)) s = `'${s}`;
  if (/[",\r\n]/.test(s)) s = `"${s.replace(/"/g, '""')}"`;
  return s;
}

export function toCsv(header: string[], rows: Cell[][]): string {
  const lines = [header.map(csvCell).join(",")];
  for (const r of rows) lines.push(r.map(csvCell).join(","));
  return lines.join("\r\n") + "\r\n";
}
