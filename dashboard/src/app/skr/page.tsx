"use client";

import { Loadable } from "@/components/Chrome";
import { SkrView } from "@/components/SkrView";
import { useApi } from "@/lib/useApi";
import type { SkrSummary } from "@/lib/types";

export default function SkrPage() {
  const state = useApi<SkrSummary>("/v1/skr/summary");
  return (
    <>
      <h1>SKR</h1>
      <p className="lede">
        SKR is what you put down, never a balance that grows by itself. At a Stack table everyone bonds the same amount
        and the phones that stay face-down to the end share what the others forfeited. A Focus Bond is the same promise
        made alone. A gift arms someone else&apos;s rig. What is forfeited and not paid to finishers is sold for ORE in an
        open auction, and that ORE goes straight into ORE&apos;s own bury instruction. Every figure below is a count or a
        sum of the program&apos;s own events.
      </p>
      <Loadable state={state}>{(d, simulated) => <SkrView data={d} simulated={simulated} />}</Loadable>
    </>
  );
}
