import { pushSubscriptionsKey } from "./push.ts";
import { isValidFcmToken, type FcmTokenRegistration } from "./fcm-token-store.ts";
import { isValidWebPushSubscription } from "./push-security.ts";

export interface PushSubscriptionRedis {
  sadd(key: string, member: string): Promise<unknown>;
  srem(key: string, member: unknown): Promise<unknown>;
}

export interface PushSubscribeDependencies {
  authenticate(request: Request): Promise<{ id: string } | null>;
  getRedis(): PushSubscriptionRedis | null;
  registerFcmToken(userId: string, token: string): Promise<FcmTokenRegistration>;
  deleteFcmToken(userId: string, token: string): Promise<void>;
}

function invalidBody(): Response {
  return Response.json({ error: "Invalid request body" }, { status: 400 });
}

function unauthorized(): Response {
  return Response.json({ error: "Unauthorized" }, { status: 401 });
}

export function createPushSubscriptionHandlers(dependencies: PushSubscribeDependencies) {
  async function authenticate(request: Request): Promise<{ id: string } | Response> {
    const user = await dependencies.authenticate(request);
    return user ?? unauthorized();
  }

  async function POST(request: Request): Promise<Response> {
    try {
      const user = await authenticate(request);
      if (user instanceof Response) return user;

      let body: unknown;
      try {
        body = await request.json();
      } catch {
        return invalidBody();
      }
      if (!body || typeof body !== "object" || Array.isArray(body)) return invalidBody();
      const data = body as Record<string, unknown>;

      if (data.platform === "android") {
        const token = data.fcm_token ?? data.token;
        if (!isValidFcmToken(token)) return Response.json({ error: "Invalid fcm_token" }, { status: 400 });
        const result = await dependencies.registerFcmToken(user.id, token);
        if (result === "conflict") {
          return Response.json({ error: "FCM token belongs to another user" }, { status: 409 });
        }
        return Response.json({ ok: true, platform: "android" });
      }

      if (data.platform !== undefined && data.platform !== "web") return invalidBody();
      if (!isValidWebPushSubscription(data.subscription)) {
        return Response.json({ error: "Invalid subscription" }, { status: 400 });
      }
      const redis = dependencies.getRedis();
      if (!redis) return Response.json({ error: "Redis not configured" }, { status: 500 });

      await redis.sadd(pushSubscriptionsKey(user.id), JSON.stringify(data.subscription));
      return Response.json({ ok: true, platform: "web" });
    } catch (error) {
      console.error("Subscribe error:", error);
      return Response.json({ error: "Internal error" }, { status: 500 });
    }
  }

  async function DELETE(request: Request): Promise<Response> {
    try {
      const user = await authenticate(request);
      if (user instanceof Response) return user;

      let body: unknown;
      try {
        body = await request.json();
      } catch {
        return invalidBody();
      }
      if (!body || typeof body !== "object" || Array.isArray(body)) return invalidBody();
      const data = body as Record<string, unknown>;

      if (data.platform === "android") {
        const token = data.fcm_token ?? data.token;
        if (!isValidFcmToken(token)) return Response.json({ error: "Invalid fcm_token" }, { status: 400 });
        await dependencies.deleteFcmToken(user.id, token);
        return Response.json({ ok: true });
      }

      if (data.platform !== undefined && data.platform !== "web") return invalidBody();
      if (!isValidWebPushSubscription(data.subscription)) {
        return Response.json({ error: "Invalid subscription" }, { status: 400 });
      }
      const redis = dependencies.getRedis();
      if (!redis) return Response.json({ error: "Redis not configured" }, { status: 500 });
      await redis.srem(pushSubscriptionsKey(user.id), JSON.stringify(data.subscription));
      return Response.json({ ok: true });
    } catch (error) {
      console.error("Unsubscribe error:", error);
      return Response.json({ error: "Internal error" }, { status: 500 });
    }
  }

  return { POST, DELETE };
}
