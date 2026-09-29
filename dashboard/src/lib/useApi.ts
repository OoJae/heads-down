"use client";

import { useEffect, useState, useSyncExternalStore } from "react";
import { client as defaultClient, type ApiClient } from "./api";
import type { DatasetInfo, Envelope } from "./types";

export type ApiState<T> =
  | { status: "unconfigured" }
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; env: Envelope<T> };

export function useApi<T>(path: string, api: ApiClient = defaultClient): ApiState<T> {
  const [state, setState] = useState<ApiState<T>>(api.base ? { status: "loading" } : { status: "unconfigured" });
  useEffect(() => {
    if (!api.base) {
      setState({ status: "unconfigured" });
      return;
    }
    const ctl = new AbortController();
    setState({ status: "loading" });
    api
      .get<T>(path, ctl.signal)
      .then((env) => setState({ status: "ready", env }))
      .catch((e: unknown) => {
        if (ctl.signal.aborted) return;
        setState({ status: "error", message: e instanceof Error ? e.message : "request failed" });
      });
    return () => ctl.abort();
  }, [path, api]);
  return state;
}

/** The dataset pinned by the first API response (null until then). */
export function useDataset(api: ApiClient = defaultClient): DatasetInfo | null {
  return useSyncExternalStore(
    (cb) => api.subscribe(cb),
    () => api.dataset(),
    () => null,
  );
}
