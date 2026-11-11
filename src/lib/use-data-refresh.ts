"use client";

import { useEffect, useRef } from "react";

import {
  subscribeDataChanged,
  type DataKind,
} from "@/lib/data-refresh";

/**
 * Run `refresh` whenever a mutation reports one of `kinds`.
 *
 * The callback is held in a ref so an inline arrow does not resubscribe on
 * every render, and so the effect depends only on the set of kinds. Refreshes
 * are expected to be idempotent fetches; they never emit a change event.
 */
export function useDataRefresh(kinds: readonly DataKind[], refresh: () => void): void {
  const refreshRef = useRef(refresh);
  useEffect(() => {
    refreshRef.current = refresh;
  }, [refresh]);

  const key = kinds.join(",");
  useEffect(() => {
    const subscribed = key.split(",").filter(Boolean) as DataKind[];
    return subscribeDataChanged(subscribed, () => refreshRef.current());
  }, [key]);
}
