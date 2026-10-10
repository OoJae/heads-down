/**
 * Log lines: one JSON object per line, `{ t, level, msg, ...fields }`.
 *
 * `info` lines go to stdout, `warn` and `error` lines to stderr, so the level and the stream never
 * disagree about an info line. Railway's log viewer takes the severity from a JSON line's `level`
 * and, without one, from the stream (stdout is info, stderr is error; its documentation, read
 * 2026-10-04). Before this every line went to stderr without a level, and `api listening` was
 * shown there as an error. The documentation does not say which of the two wins for a `warn` line
 * on stderr, and no deployment was looked at after this change.
 *
 * What a line may carry is decided where it is written, as before: messages, counts and hosts,
 * never a URL with a key, a webhook secret or a database password.
 */
export type LogLevel = "info" | "warn" | "error";

/** `level` is info when it is left out. */
export type Log = (msg: string, fields?: Record<string, unknown>, level?: LogLevel) => void;

/**
 * One line, without its newline. `t`, `level` and `msg` come first and a field of the same name
 * cannot replace them; a BigInt is written as its decimal string.
 */
export function logLine(msg: string, fields: Record<string, unknown> = {}, level: LogLevel = "info", at: Date = new Date()): string {
  const head = { t: at.toISOString(), level, msg };
  return JSON.stringify({ ...head, ...fields, ...head }, (_k, v: unknown) => (typeof v === "bigint" ? v.toString() : v));
}

export const writeLog: Log = (msg, fields, level = "info") => {
  (level === "info" ? process.stdout : process.stderr).write(logLine(msg, fields, level) + "\n");
};
