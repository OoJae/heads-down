/**
 * Attributes `Program data:` log lines (sol_log_data) to the program that emitted them.
 *
 * Any program can call sol_log_data with bytes that look like a heads_down event, so the
 * indexer must never accept a `Program data:` line just because it decodes. The runtime
 * writes `Program <id> invoke [depth]` / `Program <id> success|failed` around every
 * invocation, and a program cannot forge those lines (its own messages are always prefixed
 * `Program log:` / `Program data:` and stay inside one array element). So a replayed invoke
 * stack tells us exactly which program was executing when each data line was written.
 *
 * If the runtime truncated the logs (`Log truncated`), everything after that point is lost;
 * we return what we saw and flag the transaction so metrics can report it.
 */
import { decodeBase64Strict } from "./bytes.ts";
import { DecodeError } from "./errors.ts";

export interface ProgramDataEntry {
  /** Program executing when the line was written (top of the invoke stack). */
  programId: string;
  /** Invoke depth (1 = top-level instruction). */
  depth: number;
  /** All base64 segments of the line, concatenated (sol_log_data takes several slices). */
  data: Uint8Array;
  /** Zero-based position among ALL `Program data:` lines of the transaction. */
  lineIndex: number;
}

export interface ProgramDataParse {
  entries: ProgramDataEntry[];
  truncated: boolean;
  /** Structural anomalies (unbalanced invoke/success). The parse is still best-effort. */
  anomalies: string[];
  /** `Program data:` lines that were not valid base64, by line number. */
  badData: { line: number; error: string }[];
}

const ID = "([1-9A-HJ-NP-Za-km-z]{32,44})";
const INVOKE_RE = new RegExp(`^Program ${ID} invoke \\[(\\d{1,2})\\]$`);
const SUCCESS_RE = new RegExp(`^Program ${ID} success$`);
const FAILED_RE = new RegExp(`^Program ${ID} failed: `);
const DATA_RE = /^Program data: ([A-Za-z0-9+/= ]*)$/;

export const MAX_LOG_LINES = 20_000;
export const MAX_EVENT_BYTES = 10_240;

export function parseProgramData(logs: readonly string[]): ProgramDataParse {
  const stack: string[] = [];
  const out: ProgramDataParse = { entries: [], truncated: false, anomalies: [], badData: [] };
  if (logs.length > MAX_LOG_LINES) {
    throw new DecodeError("BAD_LENGTH", `${logs.length} log lines exceeds ${MAX_LOG_LINES}`);
  }
  let dataLines = 0;
  for (let i = 0; i < logs.length; i++) {
    const line = logs[i];
    if (typeof line !== "string") {
      out.anomalies.push(`line ${i}: not a string`);
      continue;
    }
    if (line === "Log truncated") {
      out.truncated = true;
      break;
    }
    let m = INVOKE_RE.exec(line);
    if (m) {
      const depth = Number(m[2]);
      if (depth !== stack.length + 1) out.anomalies.push(`line ${i}: invoke depth ${depth} at stack ${stack.length}`);
      stack.push(m[1]!);
      continue;
    }
    m = SUCCESS_RE.exec(line) ?? FAILED_RE.exec(line);
    if (m) {
      const top = stack.pop();
      if (top !== m[1]) out.anomalies.push(`line ${i}: ${m[1]} returned while ${top ?? "nothing"} was executing`);
      continue;
    }
    m = DATA_RE.exec(line);
    if (m) {
      const lineIndex = dataLines++;
      const top = stack[stack.length - 1];
      if (top === undefined) {
        out.anomalies.push(`line ${i}: Program data outside any invocation`);
        continue;
      }
      try {
        const segments = m[1]!.split(" ").filter((s) => s.length > 0);
        const parts = segments.map((s) => decodeBase64Strict(s, MAX_EVENT_BYTES));
        const total = parts.reduce((n, p) => n + p.length, 0);
        if (total > MAX_EVENT_BYTES) throw new DecodeError("BAD_LENGTH", `event of ${total} bytes`);
        const data = new Uint8Array(total);
        let o = 0;
        for (const p of parts) {
          data.set(p, o);
          o += p.length;
        }
        out.entries.push({ programId: top, depth: stack.length, data, lineIndex });
      } catch (e) {
        out.badData.push({ line: i, error: e instanceof Error ? e.message : String(e) });
      }
    }
  }
  return out;
}
