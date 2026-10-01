// Cross-check: run the INDEXER's own decoders (services/indexer/src/codec,
// imported read-only) on the heads_down event bytes captured from real
// LiteSVM runs (../events.json) and on real transaction logs
// (../../target/crosscheck-logs.json, written by `cargo test --test
// crosscheck -- --ignored`).
//
//   node programs/heads-down/vectors/crosscheck/indexer_events.mjs
//
// Node >= 23.6 (type stripping) is required, as for the indexer itself.
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

let rows = [];
for (const e of golden.events) {
  const bytes = hexToBytes(e.sample.hex);
  let out;
  let failed = false;
  try {
    out = decodeHdEvent(bytes);
  } catch (err) {
    failed = true;
    out = { decodeError: `${err.code ?? ""} ${err.message}` };
  }
  // Field-by-field comparison against the program's own decode.
  let verdict = "MISMATCH";
  if (out.kind === "Unknown") verdict = "UNKNOWN_TAG (not decoded)";
  else if (!failed) {
    const f = e.sample.fields;
    const pairs = {
      RigDug: [["rig", "rig"], ["roundId", "round_id"], ["lamports", "lamports"], ["mask", "mask"], ["emaEv", "ema_ev"]],
      RigSkipped: [["rig", "rig"], ["roundId", "round_id"], ["error", "error"]],
      ShiftArmed: [["rig", "rig"], ["shiftId", "shift_id"]],
      ShiftEnded: [["rig", "rig"], ["shiftId", "shift_id"], ["darkRounds", "dark_rounds"], ["roundsDug", "rounds_dug"], ["lamports", "lamports"], ["reason", "reason"]],
      SeekerVerified: [["rig", "rig"], ["sgtMint", "sgt_mint"], ["memberNumber", "member_number"]],
    }[out.kind];
    // A kind this script has no field map for (the indexer may decode newer
    // tags, such as the v1.2 SKR events 11..23) is reported, not compared.
    verdict = !pairs
      ? `DECODED as ${out.kind} (no field map here)`
      : pairs.every(([a, b]) => String(out[a]) === String(f[b]))
        ? "MATCH"
        : "MISMATCH";
  }
  rows.push({ tag: e.tag, event: e.event, length: bytes.length, indexer_expects: HD_EVENT_SIZE[e.tag] ?? null, verdict, decoded: out });
}
console.log("== events.json samples through services/indexer decodeHdEvent ==");
for (const r of rows) console.log(`tag ${r.tag} ${r.event} (${r.length} B, indexer size ${r.indexer_expects}): ${r.verdict} ${show(r.decoded)}`);

console.log("\n== RigSkipped codes through hdErrorName ==");
for (const s of golden.skip_codes) console.log(`${s.error_hex} ${s.name}: indexer says ${hdErrorName(s.error)}`);

console.log("\n== break reasons through breakReasonName ==");
for (const r of [0, 1, 2, 3, 4, 5, 6, 7, 8]) console.log(`${r}: ${breakReasonName(r)}`);

const logsPath = join(here, "../../target/crosscheck-logs.json");
if (existsSync(logsPath)) {
  console.log("\n== real transaction logs through parseProgramData + decodeHdEvent ==");
  for (const tx of JSON.parse(readFileSync(logsPath, "utf8"))) {
    const p = parseProgramData(tx.logs);
    const mine = p.entries.filter((x) => x.programId === HD);
    const decoded = mine.map((x) => {
      try {
        const d = decodeHdEvent(x.data);
        return d.kind === "Unknown" ? `Unknown(tag ${d.tag}, ${d.length} B)` : d.kind;
      } catch (err) {
        return `ERROR ${err.message}`;
      }
    });
    console.log(`${tx.name}: ${mine.length} heads_down data lines (anomalies ${p.anomalies.length}, truncated ${p.truncated}) -> ${decoded.join(", ")}`);
  }
} else {
  console.log(`\n(no ${logsPath}; run the crosscheck test first for the log check)`);
}
