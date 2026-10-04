/**
 * The log line and where it is written (src/log.ts): one JSON object per line with `t`, `level` and
 * `msg` first, info lines to stdout and the others to stderr. That the real commands write through
 * it is checked in serve.test.ts.
 */
import { afterEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { logLine, writeLog } from "../src/log.ts";

describe("log lines", () => {
  afterEach(() => vi.restoreAllMocks());

  it("are one JSON object: the time, the level, the message, then the fields", () => {
    const at = new Date("2026-10-04T05:00:00.000Z");
    expect(logLine("api listening", { dataset: "mainnet", rpc: null }, "info", at)).toBe(
      '{"t":"2026-10-04T05:00:00.000Z","level":"info","msg":"api listening","dataset":"mainnet","rpc":null}',
    );
    // Info unless the caller says otherwise, and no fields is fine.
    expect(JSON.parse(logLine("migrations applied"))).toEqual({ t: expect.stringMatching(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/), level: "info", msg: "migrations applied" });
    expect(JSON.parse(logLine("pg pool error", { error: "x" }, "warn", at))).toMatchObject({ level: "warn" });
    // One line whatever a field holds.
    expect(logLine("ingest error", { error: "first line\nsecond line" }, "error", at)).not.toContain("\n");
  });

  it("keep their time, level and message when a field has the same name", () => {
    expect(logLine("ore rounds", { level: "fatal", msg: "other", t: "never", stored: 3 }, "info", new Date(0))).toBe(
      '{"t":"1970-01-01T00:00:00.000Z","level":"info","msg":"ore rounds","stored":3}',
    );
  });

  it("write a BigInt as its decimal string", () => {
    expect(JSON.parse(logLine("ore rounds", { newest: 18_446_744_073_709_551_615n }))).toMatchObject({ newest: "18446744073709551615" });
  });

  it("go to stdout when they are info lines and to stderr when they are not", () => {
    const out = vi.spyOn(process.stdout, "write").mockImplementation(() => true);
    const err = vi.spyOn(process.stderr, "write").mockImplementation(() => true);
    writeLog("api listening", { dataset: "mainnet" });
    writeLog("rpc: transaction not yet available, will retry", {}, "warn");
    writeLog("ingest error", { error: "x" }, "error");
    /** What was written to a stream: level and message of each line, which must end with a newline. */
    const written = (spy: MockInstance) =>
      spy.mock.calls.map((c) => {
        const text = String(c[0]);
        expect(text.endsWith("\n")).toBe(true);
        const line = JSON.parse(text) as { level: string; msg: string };
        return `${line.level} ${line.msg}`;
      });
    const [toStdout, toStderr] = [written(out), written(err)];
    vi.restoreAllMocks();
    expect(toStdout).toEqual(["info api listening"]);
    expect(toStderr).toEqual(["warn rpc: transaction not yet available, will retry", "error ingest error"]);
  });
});
