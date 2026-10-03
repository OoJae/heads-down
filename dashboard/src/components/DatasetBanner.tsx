"use client";

import { useDataset } from "@/lib/useApi";
import type { DatasetInfo } from "@/lib/types";

/** Visible on every page. Simulated data gets a striped banner that cannot be missed. */
export function DatasetBannerView({ dataset }: { dataset: DatasetInfo | null }) {
  if (!dataset) return null;
  if (dataset.simulated) {
    return (
      <div className="sim-banner" role="status" aria-live="polite">
        <div className="wrap">
          <strong>SIMULATED DATA.</strong> Every number, rig, address and transaction on this page comes from the
          indexer&apos;s deterministic simulator{dataset.simSeed ? ` (seed “${dataset.simSeed}”)` : ""}. None of it
          exists on any chain, and none of it is traction. The heads_down program is not deployed on a public cluster yet.
        </div>
      </div>
    );
  }
  return (
    <div className="wrap" style={{ paddingTop: 8 }}>
      <span className="live-chip">On-chain data · Solana {dataset.name}</span>
    </div>
  );
}

export function DatasetBanner() {
  return <DatasetBannerView dataset={useDataset()} />;
}
