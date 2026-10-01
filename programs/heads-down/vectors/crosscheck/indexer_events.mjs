// Cross-check: run the INDEXER's own decoders (services/indexer/src/codec,
// imported read-only) on the heads_down event bytes captured from real
// LiteSVM runs (../events.json) and on real transaction logs
// (../../target/crosscheck-logs.json, written by `cargo test --test
// crosscheck -- --ignored`).
//
//   node programs/heads-down/vectors/crosscheck/indexer_events.mjs
//
// Node >= 23.6 (type stripping) is required, as for the indexer itself.
//
// Every line starts with a verdict:
//   [MATCH]     the indexer's result equals the program's
//   [MISMATCH]  the indexer decodes or names it differently
//   [UNKNOWN]   the indexer does not decode it (safe: it returns `Unknown`
//               with the tag and length, or a generic name; nothing breaks)
// and the script ends with the totals.
import { readFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const codec = join(here, "../../../../services/indexer/src/codec");
const { decodeHdEvent, hdErrorName, breakReasonName, HD_EVENT_SIZE } = await import(join(codec, "events.ts"));
const { parseProgramData } = await import(join(codec, "logs.ts"));

const HD = "HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p";
const golden = JSON.parse(readFileSync(join(here, "../events.json"), "utf8"));
const hexToBytes = (h) => Uint8Array.from(h.match(/../g).map((b) => parseInt(b, 16)));
const show = (v) => JSON.stringify(v, (_, x) => (typeof x === "bigint" ? x.toString() : x));
// snake_case (the program's field names) -> camelCase (the indexer's).
const camel = (s) => s.replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase());

const totals = { MATCH: 0, MISMATCH: 0, UNKNOWN: 0 };
const say = (verdict, text) => {
  totals[verdict] += 1;
  console.log(`[${verdict}] ${text}`);
};

console.log("== events.json samples through services/indexer decodeHdEvent ==");
for (const e of golden.events) {
  const bytes = hexToBytes(e.sample.hex);
  const what = `tag ${e.tag} ${e.event} (${bytes.length} B, indexer size ${HD_EVENT_SIZE[e.tag] ?? "none"})`;
  let out;
  try {
    out = decodeHdEvent(bytes);
  } catch (err) {
    say("MISMATCH", `${what}: decodeHdEvent threw ${err.code ?? ""} ${err.message}`);
    continue;
  }
  if (out.kind === "Unknown") {
    say("UNKNOWN", `${what}: not decoded ${show(out)}`);
    continue;
  }
  // Every field of the program's own decode, by name, against the indexer's.
  const wrong = [];
  if (out.kind !== e.event) wrong.push(`kind ${out.kind}`);
  for (const [name, value] of Object.entries(e.sample.fields)) {
    if (name === "tag") continue;
    if (String(out[camel(name)]) !== String(value)) wrong.push(`${name}: indexer ${out[camel(name)]} / program ${value}`);
  }
  if (wrong.length === 0) say("MATCH", `${what}: every field equal`);
  else say("MISMATCH", `${what}: ${wrong.join("; ")}`);
}

console.log("\n== RigSkipped codes through hdErrorName ==");
for (const s of golden.skip_codes) {
  const name = hdErrorName(s.error);
  // The golden name of a shared-crate code is descriptive, e.g.
  // "p256-introspect MessageMismatch (0x2560000e)": compare the variant.
  const variant = s.name.includes(" ") ? s.name.split(" ")[1] : s.name;
  if (/^unknown/i.test(name)) say("UNKNOWN", `code ${s.error_hex} ${s.name}: indexer says ${name}`);
  else if (name === variant || name.endsWith(variant)) say("MATCH", `code ${s.error_hex} ${s.name}: indexer says ${name}`);
  else say("MISMATCH", `code ${s.error_hex} ${s.name}: indexer says ${name}`);
}

console.log("\n== break reasons through breakReasonName ==");
const reasons = ["completed", "pickup", "screen_on", "freeze", "lease_lapse", "budget", "manual", "unplugged", "unlocked"];
reasons.forEach((want, code) => {
  const name = breakReasonName(code);
  const norm = (x) => String(x).toLowerCase().replace(/[^a-z]/g, "");
  if (/^unknown/i.test(name)) say("UNKNOWN", `reason ${code} ${want}: indexer says ${name}`);
  else if (norm(name) === norm(want)) say("MATCH", `reason ${code} ${want}: indexer says ${name}`);
  else say("MISMATCH", `reason ${code} ${want}: indexer says ${name}`);
});

const logsPath = join(here, "../../target/crosscheck-logs.json");
if (existsSync(logsPath)) {
  console.log("\n== real transaction logs through parseProgramData + decodeHdEvent ==");
  for (const tx of JSON.parse(readFileSync(logsPath, "utf8"))) {
    const p = parseProgramData(tx.logs);
    const mine = p.entries.filter((x) => x.programId === HD);
    let errors = 0;
    const decoded = mine.map((x) => {
      try {
        const d = decodeHdEvent(x.data);
        return d.kind === "Unknown" ? `Unknown(tag ${d.tag}, ${d.length} B)` : d.kind;
      } catch (err) {
        errors += 1;
        return `ERROR ${err.message}`;
      }
    });
    const clean = errors === 0 && p.anomalies.length === 0 && !p.truncated;
    say(
      clean ? "MATCH" : "MISMATCH",
      `${tx.name}: ${mine.length} heads_down data lines attributed (anomalies ${p.anomalies.length}, truncated ${p.truncated}) -> ${decoded.join(", ")}`,
    );
  }
} else {
  console.log(`\n(no ${logsPath}; run the crosscheck test first for the log check)`);
}

console.log(`\nTOTAL services/indexer codec: MATCH ${totals.MATCH} / MISMATCH ${totals.MISMATCH} / UNKNOWN ${totals.UNKNOWN}`);
