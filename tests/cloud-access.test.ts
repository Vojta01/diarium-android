import assert from "node:assert/strict";
import test from "node:test";

import {
  cloudFetch,
  LocalOnlyCloudAccessError,
  setLocalOnlyMode,
} from "../src/lib/cloud-access.ts";

test("local-only mode blocks cloud transport and does not queue a replay", async () => {
  const originalFetch = globalThis.fetch;
  let requests = 0;
  globalThis.fetch = (async () => {
    requests += 1;
    return new Response("ok");
  }) as typeof fetch;

  try {
    setLocalOnlyMode(true);
    await assert.rejects(
      cloudFetch("https://supabase.example.test/rest/v1/entries", {
        method: "POST",
        body: "synthetic",
      }),
      LocalOnlyCloudAccessError
    );
    assert.equal(requests, 0);

    setLocalOnlyMode(false);
    const response = await cloudFetch("https://supabase.example.test/rest/v1/entries");
    assert.equal(response.status, 200);
    assert.equal(requests, 1, "resuming online sends only the explicit new request");
  } finally {
    setLocalOnlyMode(false);
    globalThis.fetch = originalFetch;
  }
});
