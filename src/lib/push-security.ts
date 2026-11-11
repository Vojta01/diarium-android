/**
 * Browser-generated Web Push subscriptions allowed by this server:
 * - Chrome/Chromium: FCM `fcm.googleapis.com` (`/fcm/send/` or `/wp/`),
 *   matching Chromium's production push endpoint constants.
 * - Firefox: Mozilla Autopush `updates.push.services.mozilla.com/wpush/v2/`.
 * - Safari: `web.push.apple.com` (Apple documents Web Push and `*.push.apple.com`).
 * - Edge: WNS hosts below `notify.windows.com`, path `/w/?token=…`.
 *
 * Sources: Chromium `push_messaging_constants.cc`; Apple “Sending web push
 * notifications in web apps and browsers”; Mozilla Push API; Microsoft WNS.
 * No staging/test hosts or arbitrary custom endpoints are accepted. `web-push`
 * uses a direct HTTPS request and treats 3xx as an error; it does not follow
 * redirects, so an endpoint cannot redirect the sender into a private network.
 */

export interface WebPushSubscription {
  endpoint: string;
  expirationTime?: number | null;
  keys: {
    p256dh: string;
    auth: string;
  };
}

const MAX_ENDPOINT_LENGTH = 2048;
const BROWSER_KEY_P256DH = /^[A-Za-z0-9_-]{87}$/;
const BROWSER_KEY_AUTH = /^[A-Za-z0-9_-]{22}$/;
function hasAllowedProviderAndPath(url: URL): boolean {
  const { hostname, pathname } = url;
  if (hostname === "fcm.googleapis.com") {
    return /^\/(?:fcm\/send|wp)\/[A-Za-z0-9%._~:+-]+\/?$/.test(pathname);
  }
  if (hostname === "updates.push.services.mozilla.com") {
    return /^\/wpush\/v2\/[A-Za-z0-9%._~:+-]+\/?$/.test(pathname);
  }
  if (hostname === "web.push.apple.com") {
    return /^\/[A-Za-z0-9%._~:+-]+\/?$/.test(pathname);
  }
  if (/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.notify\.windows\.com$/.test(hostname)) {
    const tokens = url.searchParams.getAll("token");
    return pathname === "/w/" && tokens.length === 1 && tokens[0].length > 0 && [...url.searchParams.keys()].length === 1;
  }
  return false;
}

/** Validate a browser's Web Push endpoint and encryption key shapes. */
export function isValidWebPushSubscription(value: unknown): value is WebPushSubscription {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const candidate = value as Partial<WebPushSubscription>;
  if (
    typeof candidate.endpoint !== "string" ||
    candidate.endpoint.length === 0 ||
    candidate.endpoint.length > MAX_ENDPOINT_LENGTH ||
    candidate.endpoint.trim() !== candidate.endpoint ||
    /[\u0000-\u0020\u007f]/.test(candidate.endpoint) ||
    !candidate.keys ||
    typeof candidate.keys !== "object" ||
    typeof candidate.keys.p256dh !== "string" ||
    !BROWSER_KEY_P256DH.test(candidate.keys.p256dh) ||
    typeof candidate.keys.auth !== "string" ||
    !BROWSER_KEY_AUTH.test(candidate.keys.auth)
  ) {
    return false;
  }

  let url: URL;
  try {
    url = new URL(candidate.endpoint);
  } catch {
    return false;
  }

  // URL normalizes an explicit default :443 to an empty `.port`; reject ports
  // in the original authority as well as non-default ports in the parsed URL.
  const authority = candidate.endpoint.match(/^https:\/\/([^/?#]+)/i)?.[1];
  if (!authority || authority.includes(":")) return false;

  return (
    url.protocol === "https:" &&
    url.port === "" &&
    url.username === "" &&
    url.password === "" &&
    url.hash === "" &&
    hasAllowedProviderAndPath(url) &&
    ((url.hostname === "wns2-bl2p.notify.windows.com" || url.hostname.endsWith(".notify.windows.com"))
      ? true
      : url.search === "")
  );
}

/** Parse a Redis value without trusting data stored before endpoint validation existed. */
export function parseStoredWebPushSubscription(value: unknown): WebPushSubscription | null {
  let candidate = value;
  if (typeof candidate === "string") {
    try {
      candidate = JSON.parse(candidate);
    } catch {
      return null;
    }
  }
  return isValidWebPushSubscription(candidate) ? candidate : null;
}
