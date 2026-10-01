"use client";

import { useEffect, useState } from "react";
import { Loadable } from "@/components/Chrome";
import { SkipHistogram, SkipList } from "@/components/SkipsView";
import { client, isRigAddress } from "@/lib/api";
import { formatInt } from "@/lib/format";
import { useApi, useDataset } from "@/lib/useApi";
import type { SkipItem, Skips } from "@/lib/types";

export default function SkipsPage() {
  const [draft, setDraft] = useState("");
  const [rig, setRig] = useState<string | null>(null);
  const [code, setCode] = useState<number | null>(null);
  const query = `${rig ? `&rig=${rig}` : ""}${code !== null ? `&error=${code}` : ""}`;
  const state = useApi<Skips>(`/v1/skips?limit=50${query}`);
  const dataset = useDataset();
  // "Load more": later pages are appended; a new filter starts over.
  const [more, setMore] = useState<{ items: SkipItem[]; cursor: string | null; error: string | null }>({ items: [], cursor: null, error: null });
  useEffect(() => {
    setMore({ items: [], cursor: state.status === "ready" ? state.env.data.nextCursor : null, error: null });
  }, [state]);
  const csv = client.url(`/v1/export/skips.csv?days=30${query}`);
  const badRig = draft !== "" && !isRigAddress(draft);

  return (
    <>
      <h1>Skips: when the program said no</h1>
      <p className="lede">
        Every time a crank asks the heads_down program to dig and a check fails, the program skips that rig and logs a
        RigSkipped event with the exact reason. A replayed heartbeat, a phone that went quiet, a price gate that stayed closed:
        each one is refused on chain, and each refusal is listed here.
      </p>
      <form
        className="filter-row"
        onSubmit={(e) => {
          e.preventDefault();
          if (!badRig) setRig(draft === "" ? null : draft);
        }}
      >
        <label className="small">
          Rig{" "}
          <input
            className="theme-toggle mono"
            value={draft}
            onChange={(e) => setDraft(e.target.value.trim())}
            placeholder="all rigs (paste a rig address)"
            aria-invalid={badRig}
            size={44}
          />
        </label>
        <button type="submit" className="btn" disabled={badRig}>
          Filter
        </button>
        {rig || code !== null ? (
          <button
            type="button"
            className="btn"
            onClick={() => {
              setDraft("");
              setRig(null);
              setCode(null);
            }}
          >
            Clear filters
          </button>
        ) : null}
        {badRig ? <span className="small pill-warn">Not a rig address (base58, 32 bytes)</span> : null}
      </form>
      <Loadable state={state}>
        {(d, simulated, asOf) => (
          <>
            <section className="card" aria-label="Skips by reason">
              <h2>Why rigs were skipped{rig ? " (this rig)" : ""}</h2>
              <SkipHistogram histogram={d.histogram} selected={code} onSelect={setCode} />
            </section>
            <section className="card section" aria-label="Skip list">
              <h2>
                {code === null ? "Latest skips" : `Latest ${d.histogram.find((h) => h.code === code)?.name ?? "matching"} skips`}{" "}
                <span className="muted small">{formatInt(d.total)} in all</span>
              </h2>
              <SkipList items={[...d.items, ...more.items]} simulated={simulated} now={asOf} />
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 12 }}>
                {more.cursor ? (
                  <button
                    type="button"
                    className="btn"
                    onClick={() => {
                      client
                        .get<Skips>(`/v1/skips?limit=50${query}&cursor=${encodeURIComponent(more.cursor!)}`)
                        .then((env) => setMore((m) => ({ items: [...m.items, ...env.data.items], cursor: env.data.nextCursor, error: null })))
                        .catch((e: unknown) => setMore((m) => ({ ...m, error: e instanceof Error ? e.message : "request failed" })));
                    }}
                  >
                    Load more
                  </button>
                ) : null}
                {csv ? (
                  <a className="btn" href={csv} download>
                    ⬇ CSV of these skips (30 days){dataset?.simulated ? <span className="sim-badge">SIM</span> : null}
                  </a>
                ) : null}
                {more.error ? (
                  <span className="small pill-warn" role="alert">
                    {more.error}
                  </span>
                ) : null}
              </div>
            </section>
          </>
        )}
      </Loadable>
    </>
  );
}
