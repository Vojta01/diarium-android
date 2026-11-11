/**
 * Shared Redis (Upstash) client module.
 * 
 * Provides a singleton Redis client for all routes that need it.
 * Uses dynamic import so the build doesn't fail when env vars are missing.
 */

let _redis: any = null;

export function getRedis() {
  if (!_redis && process.env.UPSTASH_REDIS_REST_URL && process.env.UPSTASH_REDIS_REST_TOKEN) {
    // Dynamic import so build doesn't fail without env vars
    const { Redis } = require("@upstash/redis") as typeof import("@upstash/redis");
    _redis = new Redis({
      url: process.env.UPSTASH_REDIS_REST_URL,
      token: process.env.UPSTASH_REDIS_REST_TOKEN,
    });
  }
  return _redis;
}
