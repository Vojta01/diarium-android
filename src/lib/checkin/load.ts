/**
 * Load outcomes are three-valued on purpose: "no entry" is a valid empty form,
 * "failed" is not. The old `getEntry` collapsed both into `null`, so a network
 * failure rendered an empty form over a stored day and the next save erased it.
 */
import { isCheckInError, loadFailedError, type CheckInError } from "./errors.ts";

export type LoadResult<T> =
  | { status: "entry"; entry: T }
  | { status: "empty" }
  | { status: "failed"; error: CheckInError };

export async function loadCheckIn<T>(
  date: string,
  read: (date: string) => Promise<T | null>
): Promise<LoadResult<T>> {
  try {
    const entry = await read(date);
    return entry ? { status: "entry", entry } : { status: "empty" };
  } catch (error) {
    return {
      status: "failed",
      error: isCheckInError(error) ? error : loadFailedError(error),
    };
  }
}
