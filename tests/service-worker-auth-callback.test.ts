import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import test from "node:test";

const SERVICE_WORKER_SOURCE = readFileSync(new URL("../public/sw.js", import.meta.url), "utf8");

type TestRequest = { url: string; method: string; mode: string };
type FetchEvent = {
  request: TestRequest;
  respondWith(response: Promise<Response>): void;
};

type WorkerHarness = {
  cachedResponses: Map<string, Response>;
  matchCalls: string[];
  openedCaches: string[];
  storedRequests: string[];
  fetchRequests: string[];
  dispatchFetch(requestUrl: string): Promise<Response>;
};

function createWorkerHarness(network: "online" | "offline"): WorkerHarness {
  const listeners = new Map<string, (event: FetchEvent) => void>();
  const cachedResponses = new Map<string, Response>();
  const matchCalls: string[] = [];
  const openedCaches: string[] = [];
  const storedRequests: string[] = [];
  const fetchRequests: string[] = [];
  const self = {
    addEventListener(type: string, listener: (event: FetchEvent) => void) {
      listeners.set(type, listener);
    },
    skipWaiting() {},
    clients: { claim() {} },
    location: { origin: "https://diarium.example" },
    registration: { unregister() {} },
  };
  const caches = {
    keys: async () => ["diarium-v5"],
    delete: async () => true,
    match: async (request: TestRequest) => {
      matchCalls.push(request.url);
      return cachedResponses.get(request.url);
    },
    open: async (name: string) => {
      openedCaches.push(name);
      return {
        put: async (request: TestRequest) => {
          storedRequests.push(request.url);
        },
      };
    },
  };
  const fetch = async (request: TestRequest) => {
    fetchRequests.push(request.url);
    if (network === "offline") throw new Error("synthetic offline network");
    return new Response("network callback response", { status: 200 });
  };

  runInNewContext(SERVICE_WORKER_SOURCE, {
    self,
    caches,
    fetch,
    URL,
    Response,
    AbortController,
    setTimeout,
    clearTimeout,
    Promise,
  });

  return {
    cachedResponses,
    matchCalls,
    openedCaches,
    storedRequests,
    fetchRequests,
    dispatchFetch(requestUrl: string) {
      let responsePromise: Promise<Response> | undefined;
      const event: FetchEvent = {
        request: { url: requestUrl, method: "GET", mode: "navigate" },
        respondWith(response) {
          responsePromise = response;
        },
      };
      listeners.get("fetch")?.(event);
      assert.ok(responsePromise, "service worker should handle a GET request");
      return responsePromise;
    },
  };
}

const CALLBACK_REQUESTS = [
  "https://diarium.example/auth-callback.html?code=synthetic-pkce-code&state=synthetic-state",
  "https://diarium.example/auth/callback?access_token=synthetic-access-token&refresh_token=synthetic-refresh-token",
];

test("callback requests never retrieve an old cached response or store a fresh response", async () => {
  for (const requestUrl of CALLBACK_REQUESTS) {
    const offlineWorker = createWorkerHarness("offline");
    offlineWorker.cachedResponses.set(
      requestUrl,
      new Response("stale cache contained synthetic callback credentials", { status: 200 })
    );

    await assert.rejects(
      offlineWorker.dispatchFetch(requestUrl),
      /synthetic offline network/,
      "offline callbacks must fail network-only rather than return cached data"
    );
    assert.deepEqual(offlineWorker.matchCalls, [], "callback requests must not consult old caches");
    assert.deepEqual(offlineWorker.openedCaches, [], "offline callbacks must not open a cache");
    assert.deepEqual(offlineWorker.storedRequests, [], "offline callbacks must not store a response");

    const onlineWorker = createWorkerHarness("online");
    const onlineResponse = await onlineWorker.dispatchFetch(requestUrl);
    assert.equal(await onlineResponse.text(), "network callback response");
    await new Promise<void>((resolve) => setImmediate(resolve));
    assert.deepEqual(onlineWorker.matchCalls, [], "online callback requests must not consult old caches");
    assert.deepEqual(onlineWorker.openedCaches, [], "online callback requests must not open a cache");
    assert.deepEqual(onlineWorker.storedRequests, [], "callback responses must never be cached");
  }
});
