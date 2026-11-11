const CACHE = "diarium-v8";
const PREVIOUS_CACHES = [
  "diarium-v1",
  "diarium-v3",
  "diarium-v4",
  "diarium-v5",
  "diarium-v6",
  "diarium-v7",
];
const OWNED_CACHES = [CACHE, ...PREVIOUS_CACHES];
const FETCH_TIMEOUT_MS = 8000;

// These are the app's public shell resources. Arbitrary same-origin paths are
// deliberately network-only; only immutable Next build assets use a prefix.
const PUBLIC_STATIC_PATHS = new Set([
  "/favicon.ico",
  "/manifest.json",
  "/icon.svg",
  "/icon-192.png",
  "/icon-512.png",
  "/icon-512-maskable.png",
]);
const SAFE_VARY_HEADERS = new Set([
  "accept-encoding",
  "accept-language",
  "rsc",
  "next-router-state-tree",
  "next-router-prefetch",
  "next-router-segment-prefetch",
]);

self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys.filter((key) => PREVIOUS_CACHES.includes(key)).map((key) => caches.delete(key))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("message", (event) => {
  if (event.data?.type === "SKIP_WAITING") {
    self.skipWaiting();
  }
  if (event.data?.type === "UNREGISTER_SELF") {
    // Graceful self-unregister: remove only this app's known caches.
    self.skipWaiting();
    event.waitUntil(
      caches.keys()
        .then((keys) => Promise.all(
          keys.filter((key) => OWNED_CACHES.includes(key)).map((key) => caches.delete(key))
        ))
        .then(() => self.registration.unregister())
    );
  }
});

function isApiRequest(url) {
  return url.pathname === "/api" || url.pathname.startsWith("/api/");
}

function isAuthCallbackRequest(url) {
  return url.pathname.startsWith("/auth/")
    || url.pathname === "/auth-callback.html"
    || url.searchParams.has("code")
    || url.searchParams.has("access_token")
    || url.searchParams.has("refresh_token");
}

function hasPrivateCacheDirective(response) {
  const cacheControl = response.headers.get("Cache-Control") || "";
  return cacheControl.split(",").some((directive) =>
    /^(?:private|no-store|no-cache)(?:\s*=|\s|$)/i.test(directive.trim())
  );
}

function hasUnsafeVary(response) {
  if (!response.headers.has("Vary")) return false;
  const vary = response.headers.get("Vary") || "";
  return vary.split(",").some((header) => {
    const name = header.trim().toLowerCase();
    return !name || !SAFE_VARY_HEADERS.has(name);
  });
}

function isCacheablePublicResponse(response) {
  return response.status === 200
    && response.ok
    && !response.redirected
    && !hasPrivateCacheDirective(response)
    && !hasUnsafeVary(response)
    && !response.headers.has("Set-Cookie");
}

function isWhitelistedPublicRequest(request, url) {
  if (
    request.method !== "GET"
    || url.origin !== self.location.origin
    || url.search
  ) {
    return false;
  }

  if (url.pathname === "/") return request.mode === "navigate";
  if (PUBLIC_STATIC_PATHS.has(url.pathname)) return true;

  if (!url.pathname.startsWith("/_next/static/")) return false;
  if (url.pathname.split("/").some((segment) => segment === "." || segment === "..")) return false;
  return /\.(?:js|css|woff2?|ttf|ico|svg|png|webp|avif)$/i.test(url.pathname);
}

function createCredentiallessShellRequest(request) {
  return new Request(request.url, {
    method: "GET",
    headers: request.headers,
    mode: "same-origin",
    credentials: "omit",
    redirect: "follow",
  });
}

/**
 * Fetch with a timeout. The public shell may use a cached copy if this times out.
 */
function fetchWithTimeout(request, timeoutMs) {
  return new Promise((resolve, reject) => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    fetch(request, { signal: controller.signal })
      .then((response) => {
        clearTimeout(timer);
        resolve(response);
      })
      .catch((error) => {
        clearTimeout(timer);
        reject(error);
      });
  });
}

/**
 * Minimal offline fallback HTML — used only for a root navigation when there
 * is no safely cached public app shell.
 */
const OFFLINE_FALLBACK_HTML =
  '<!DOCTYPE html><html lang="cs"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Diarium — offline</title><style>body{background:#0f0f23;color:#a5b4fc;font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;text-align:center;padding:2rem}a{color:#6366f1;text-decoration:underline}</style></head><body><div><p>Aplikace je momentálně offline.</p><p style="margin-top:1rem;font-size:0.85rem;color:rgba(165,180,252,0.5)">Zkus <a href="/sw-reset">obnovit aplikaci</a> nebo to zopakuj později.</p></div></body></html>';

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  const publicShellNavigation = request.mode === "navigate"
    && url.pathname === "/"
    && !url.search;

  // Sensitive, authenticated, cross-origin, and non-GET requests are always
  // network-only. In particular, never consult a prior cache for API exports.
  if (
    isApiRequest(url)
    || isAuthCallbackRequest(url)
    || url.pathname === "/sw-reset"
    || url.pathname === "/sw-reset.html"
    || request.headers.has("Authorization")
    || (request.credentials === "include" && !publicShellNavigation)
    || request.method !== "GET"
    || url.origin !== self.location.origin
  ) {
    event.respondWith(fetch(request));
    return;
  }

  // An explicit allowlist keeps default same-origin cookies away from generic
  // routes whose contents may depend on the signed-in account. Navigations are
  // credentialed by browsers even when the JS default is "same-origin". For
  // the verified static root shell only, make a credentialless network request
  // so no account cookie can affect the response that enters CacheStorage.
  if (!isWhitelistedPublicRequest(request, url)) {
    event.respondWith(fetch(request));
    return;
  }
  const cacheRequest = publicShellNavigation && request.credentials === "include"
    ? createCredentiallessShellRequest(request)
    : request;

  const networkResponse = fetchWithTimeout(cacheRequest, FETCH_TIMEOUT_MS);
  let cacheWrite = Promise.resolve();
  const responseForClient = networkResponse.then((response) => {
    if (isCacheablePublicResponse(response)) {
      // Clone synchronously before handing the response to respondWith(). Cache
      // open/write is asynchronous, and must not race with the page consuming it.
      try {
        const cacheCopy = response.clone();
        cacheWrite = caches.open(CACHE)
          .then((cache) => cache.put(cacheRequest, cacheCopy))
          .catch(() => {});
      } catch {}
    }
    return response;
  });

  // Register lifetime synchronously. The clone above occurs in the shared
  // response promise before the page receives the original body stream.
  event.waitUntil(responseForClient.then(() => cacheWrite, () => {}));
  event.respondWith(responseForClient.catch(() =>
    caches.open(CACHE)
      .then((cache) => cache.match(cacheRequest))
      .then((cached) => {
        if (cached && isCacheablePublicResponse(cached)) return cached;
        if (publicShellNavigation) {
          return new Response(OFFLINE_FALLBACK_HTML, {
            status: 200,
            headers: { "Content-Type": "text/html; charset=utf-8" },
          });
        }
        return new Response("Offline", { status: 503 });
      })
  ));
});

// --- PUSH NOTIFICATIONS ---
self.addEventListener("push", (event) => {
  let data = { title: "Diarium", body: "Nezapomeň vyplnit dnešní záznam! 🖊️" };
  try {
    if (event.data) {
      const parsed = event.data.json();
      data = { ...data, ...parsed };
    }
  } catch {}

  event.waitUntil(
    self.registration.showNotification(data.title, {
      body: data.body,
      icon: "/icon-192.png",
      badge: "/icon-192.png",
      vibrate: [200, 100, 200],
      tag: "diarium-reminder",
      requireInteraction: true,
      data: { url: "/" },
    })
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(
    clients.matchAll({ type: "window", includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if (client.url.includes(self.location.origin) && "focus" in client) {
          return client.focus();
        }
      }
      return clients.openWindow("/");
    })
  );
});
