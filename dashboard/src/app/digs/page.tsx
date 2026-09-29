"use client";

import { Loadable } from "@/components/Chrome";
import { DigsFeed } from "@/components/DigsFeed";
import { useApi } from "@/lib/useApi";
import type { FeedItem } from "@/lib/types";

export default function DigsPage() {
  const state = useApi<FeedItem[]>("/v1/digs/recent?limit=50");
  return (
    <>
      <h1>Recent digs</h1>
      <p className="lede">
        Each row is one rig&apos;s ORE deploy, made only because its phone&apos;s P-256 heartbeat for that round verified on-chain.
        It is the program&apos;s RigDug event paired with ORE&apos;s own DeployEvent from the same transaction.
      </p>
      <Loadable state={state}>
        {(items, simulated, asOf) => (
          <section className="card" aria-label="Recent digs">
            <DigsFeed items={items} simulated={simulated} now={asOf} />
          </section>
        )}
      </Loadable>
    </>
  );
}
