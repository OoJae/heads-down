"use client";

import { Loadable } from "@/components/Chrome";
import { OverviewView } from "@/components/Overview";
import { useApi } from "@/lib/useApi";
import type { Summary } from "@/lib/types";

export default function OverviewPage() {
  const state = useApi<Summary>("/v1/summary");
  return (
    <>
      <h1>Your phone&apos;s night shift, in numbers</h1>
      <p className="lede">
        Phones lying face-down mine ORE inside their owners&apos; own ORE Automation accounts, and only while the phone&apos;s
        hardware key keeps signing. Each number below links to transactions and accounts you can open on an explorer.
      </p>
      <Loadable state={state}>{(s, simulated) => <OverviewView s={s} simulated={simulated} />}</Loadable>
    </>
  );
}
