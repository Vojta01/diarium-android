import {
  parseStoredWebPushSubscription,
  type WebPushSubscription,
} from "./push-security.ts";

export interface PushSubscriptionRedisStore {
  srem(key: string, member: unknown): Promise<unknown>;
}

/**
 * Revalidate every Redis value before making a network request. Legacy or
 * modified malicious records are pruned and can never reach web-push.
 */
export async function deliverStoredWebPushSubscriptions<T>(options: {
  key: string;
  stored: readonly T[];
  redis: PushSubscriptionRedisStore;
  payload: string;
  sendNotification: (subscription: WebPushSubscription, payload: string) => Promise<unknown>;
}): Promise<{ sent: number; errors: number }> {
  const valid: Array<{ raw: T; subscription: WebPushSubscription }> = [];
  const invalid: T[] = [];

  for (const raw of options.stored) {
    const subscription = parseStoredWebPushSubscription(raw);
    if (!subscription) invalid.push(raw);
    else valid.push({ raw, subscription });
  }

  await Promise.all(invalid.map((raw) => options.redis.srem(options.key, raw)));

  let sent = 0;
  let errors = 0;
  await Promise.all(valid.map(async ({ raw, subscription }) => {
    try {
      await options.sendNotification(subscription, options.payload);
      sent += 1;
    } catch (error) {
      errors += 1;
      const statusCode = (error as { statusCode?: unknown } | null)?.statusCode;
      if (statusCode === 404 || statusCode === 410) {
        await options.redis.srem(options.key, raw);
      }
    }
  }));

  return { sent, errors };
}
