/**
 * The post-save reflection dialog is driven by the same reflection state as the
 * inline section. It has exactly one phase at a time, so the UI cannot show a
 * spinner over an error or a stale text while a new request is running.
 */
import type { CheckInError } from "./errors.ts";

export type PostSaveReflectionPhase = "loading" | "error" | "reflection";

export function postSaveReflectionPhase(input: {
  loading: boolean;
  error: CheckInError | null;
  reflection: string | null;
}): PostSaveReflectionPhase {
  if (input.loading) return "loading";
  if (input.error) return "error";
  if (input.reflection?.trim()) return "reflection";
  return "error";
}
