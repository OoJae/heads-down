"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { Loadable } from "@/components/Chrome";
import { HaulView } from "@/components/HaulView";
import { client, getHaul, isRigAddress, type HaulResult } from "@/lib/api";
import { breakReasonText } from "@/lib/haul";
import { formatUtc } from "@/lib/format";
import { useApi } from "@/lib/useApi";
import type { RigShifts } from "@/lib/types";

type HaulState = { status: "idle" } | { status: "loading" } | { status: "error"; message: string } | { status: "done"; result: HaulResult };

function useHaul(rig: string | null, shift: string, ready: boolean): HaulState {
  const [state, setState] = useState<HaulState>({ status: "idle" });
  useEffect(() => {
    if (!rig || !ready || !client.base) return;
    const ctl = new AbortController();
    setState({ status: "loading" });
    getHaul(client, rig, shift, undefined, ctl.signal)
      .then((result) => setState({ status: "done", result }))
      .catch((e: unknown) => {
        if (!ctl.signal.aborted) setState({ status: "error", message: e instanceof Error ? e.message : "request failed" });
      });
    return () => ctl.abort();
  }, [rig, shift, ready]);
  return state;
}

function HaulPage() {
  const params = useSearchParams();
  const router = useRouter();
  const rigParam = params.get("rig");
  const rig = isRigAddress(rigParam) ? rigParam : null;
  const shiftParam = params.get("shift");
  const shift = shiftParam && /^\d{1,20}$/.test(shiftParam) ? shiftParam : "latest";
  const [draft, setDraft] = useState(rigParam ?? "");
  // The shift list is enveloped: it pins the dataset before the (non-enveloped) haul is read.
  const shifts = useApi<RigShifts>(rig ? `/v1/rigs/${rig}/shifts` : "/v1/health");
  const haul = useHaul(rig, shift, shifts.status === "ready");
  const go = (r: string, s?: string) => router.push(`/haul/?rig=${encodeURIComponent(r)}${s ? `&shift=${s}` : ""}`);

  return (
    <>
      <h1>Hauls by rig</h1>
      <p className="lede">
        The phone&apos;s morning reveal, for any rig: every ORE round of the shift, where the rig dug, which tile won, ORE mined by
        ORE&apos;s own rules, and the effective price against the market. Built from chain data only.
      </p>
      <form
        className="filter-row"
        onSubmit={(e) => {
          e.preventDefault();
          if (isRigAddress(draft)) go(draft);
        }}
      >
        <label className="small">
          Rig{" "}
          <input className="theme-toggle mono" value={draft} onChange={(e) => setDraft(e.target.value.trim())} placeholder="rig address (base58)" size={44} />
        </label>
        <button type="submit" className="btn" disabled={!isRigAddress(draft)}>
          Show haul
        </button>
        {rig && shifts.status === "ready" && shifts.env.data.shifts.length > 0 ? (
          <label className="small">
            Shift{" "}
            <select className="theme-toggle" value={shift} onChange={(e) => go(rig, e.target.value === "latest" ? undefined : e.target.value)}>
              <option value="latest">latest</option>
              {shifts.env.data.shifts.map((s) => (
                <option key={`${s.shiftId}-${s.signature}`} value={s.shiftId}>
                  #{s.shiftId} · ended {formatUtc(s.endedAt)} · {breakReasonText(s.reason)}
                </option>
              ))}
            </select>
          </label>
        ) : null}
      </form>
      {!rig ? (
        <div className="card state">
          Paste a rig address, or open one from the <Link href="/digs/">recent digs</Link> or <Link href="/skips/">skips</Link>.
        </div>
      ) : (
        <Loadable state={shifts}>
          {(_d, simulated) => {
            if (haul.status === "idle" || haul.status === "loading") return <div className="card state" aria-busy="true">Loading…</div>;
            if (haul.status === "error") {
              return (
                <div className="card state error" role="alert">
                  Could not load the haul: {haul.message}
                </div>
              );
            }
            const r = haul.result;
            if (r.status === "none") {
              return (
                <div className="card state" role="status">
                  {r.message}
                  {r.retryAfterS ? ` Try again in about ${r.retryAfterS} s.` : ""}
                </div>
              );
            }
            return <HaulView haul={r.haul} simulated={simulated || r.haul.simulated} dataset={r.dataset} />;
          }}
        </Loadable>
      )}
    </>
  );
}

export default function Page() {
  return (
    <Suspense fallback={<div className="card state">Loading…</div>}>
      <HaulPage />
    </Suspense>
  );
}
