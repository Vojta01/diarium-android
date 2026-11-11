import type { NextRequest } from "next/server";

/**
 * Shared cron authorization for server-only endpoints.
 *
 * SECURITY — why this file exists (2026-09-10)
 * -------------------------------------------
 * Each route used to inline this expression:
 *
 *     const isCron = !cronSecret
 *       || authHeader === `Bearer ${cronSecret}`
 *       || querySecret === cronSecret
 *       || request.headers.get("x-vercel-cron") === "1";
 *
 * That has two holes:
 *
 *   1. `!cronSecret` — when CRON_SECRET is missing from the environment, EVERY
 *      caller is treated as a trusted cron. The protection silently inverts.
 *   2. `x-vercel-cron` — this is a CALLER-SUPPLIED header. Anyone can send it
 *      and be treated as cron. Verified live on production: a forged header got
 *      past the auth gate of `/api/cron/ai-report` (400 instead of 401) and
 *      returned 200 from `/api/backfill-scale-entries`.
 *
 * The rule now: **only a non-empty secret that actually matches counts.**
 * If CRON_SECRET is absent we FAIL CLOSED (nobody is cron). Routes that also
 * accept a real user JWT stay usable for real users either way.
 *
 * `?secret=` is kept because cron runners often cannot set custom headers.
 * Do NOT reintroduce header-based trust — `vercel.json` declares no crons, so
 * nothing legitimate depends on it.
 */
export function isCronAuthorized(request: NextRequest): boolean {
  const cronSecret = process.env.CRON_SECRET || "";
  if (cronSecret.length === 0) return false;

  if (request.headers.get("authorization") === `Bearer ${cronSecret}`) {
    return true;
  }

  const querySecret = new URL(request.url).searchParams.get("secret");
  return querySecret === cronSecret;
}
