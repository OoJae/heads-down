"use client";

import { useState } from "react";
import { Loadable } from "@/components/Chrome";
import { ShareView } from "@/components/ShareView";
import { useApi } from "@/lib/useApi";
import type { ShareByHour } from "@/lib/types";

const ZONES = [
  { label: "WAT (Lagos, Abuja)", minutes: 60 },
  { label: "UTC", minutes: 0 },
  { label: "EAT (Nairobi)", minutes: 180 },
  { label: "IST", minutes: 330 },
  { label: "PHT (Manila)", minutes: 480 },
  { label: "KST (Seoul)", minutes: 540 },
  { label: "BRT (São Paulo)", minutes: -180 },
];
const WINDOWS = [1, 7, 30];

export default function SharePage() {
  const [tz, setTz] = useState(60);
  const [days, setDays] = useState(7);
  const state = useApi<ShareByHour>(`/v1/share-by-hour?tz=${tz}&days=${days}`);
  return (
    <>
      <h1>Share of ORE miners, by hour</h1>
      <p className="lede">
        How much of ORE&apos;s mining crowd is made up of Heads Down rigs at each hour of the night. This is the number ORE can
        check against its own logs.
      </p>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 12, marginBottom: 12 }}>
        <label className="small">
          Time zone{" "}
          <select value={tz} onChange={(e) => setTz(Number(e.target.value))} className="theme-toggle" style={{ marginLeft: 4 }}>
            {ZONES.map((z) => (
              <option key={z.minutes} value={z.minutes}>
                {z.label}
              </option>
            ))}
          </select>
        </label>
        <label className="small">
          Window{" "}
          <select value={days} onChange={(e) => setDays(Number(e.target.value))} className="theme-toggle" style={{ marginLeft: 4 }}>
            {WINDOWS.map((d) => (
              <option key={d} value={d}>
                last {d} day{d === 1 ? "" : "s"}
              </option>
            ))}
          </select>
        </label>
      </div>
      <Loadable state={state}>{(d, simulated) => <ShareView data={d} simulated={simulated} />}</Loadable>
    </>
  );
}
