import { NextRequest } from "next/server";
import webpush from "web-push";
import { VAPID_PUBLIC_KEY, VAPID_EMAIL } from "@/lib/vapid";
import { getRedis } from "@/lib/redis";
import { isCronAuthorized } from "@/lib/cron-auth";
import { pushSubscriptionsKey } from "@/lib/push";
import { deliverStoredWebPushSubscriptions } from "@/lib/push-delivery";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

let _vapidReady = false;
function ensureVapid() {
  if (!_vapidReady && process.env.VAPID_PRIVATE_KEY) {
    webpush.setVapidDetails(
      VAPID_EMAIL,
      VAPID_PUBLIC_KEY,
      process.env.VAPID_PRIVATE_KEY
    );
    _vapidReady = true;
  }
  return _vapidReady;
}

export async function GET(request: NextRequest) {
  try {
    // Server-only — requires the CRON_SECRET (Bearer or ?secret=). See
    // src/lib/cron-auth.ts: the caller-supplied `x-vercel-cron` header used to
    // be trusted here, which let anyone trigger a push to all subscribers.
    const url = new URL(request.url);
    if (!isCronAuthorized(request)) {
      return Response.json({ error: "Unauthorized" }, { status: 401 });
    }

    const message = JSON.parse(url.searchParams.get("message") || "{}");
    // Push is addressed to one account. Without a user id there is no safe
    // recipient: the old behaviour fanned out to every stored subscription,
    // i.e. one user's reminder reached all users' devices.
    const userId = (url.searchParams.get("userId") || message.userId || "").trim();
    if (!userId) {
      return Response.json(
        { error: "userId is required; broadcasts are not supported" },
        { status: 400 }
      );
    }

    const title = message.title || "Diarium";
    const body = message.body || "Nezapomeň vyplnit dnešní záznam! 🖊️";

    let webSent = 0;
    let webErrors = 0;

    // ── Web push (PWA/browser) via Redis subscriptions ──
    // Variant A: Android notifications are scheduled LOCALLY in the app at
    // user-chosen times; the server only keeps web push for PWA/browser users.
    // (FCM is intentionally not used here anymore to avoid duplicates.)
    const redis = getRedis();
    const key = pushSubscriptionsKey(userId);
    if (redis && ensureVapid()) {
      const rawSubs: unknown[] = await redis.smembers(key);
      const payload = JSON.stringify({ title, body });
      const result = await deliverStoredWebPushSubscriptions({
        key,
        stored: rawSubs,
        redis,
        payload,
        // web-push makes a direct HTTPS request; non-2xx redirects are errors,
        // never followed to a caller-controlled destination.
        sendNotification: (subscription, messagePayload) =>
          webpush.sendNotification(subscription, messagePayload),
      });
      webSent = result.sent;
      webErrors = result.errors;
    }

    return Response.json({
      userId,
      webSent,
      webErrors: webErrors > 0 ? webErrors : undefined,
    });
  } catch (err) {
    console.error("Send error:", err);
    return Response.json({ error: "Internal error" }, { status: 500 });
  }
}
