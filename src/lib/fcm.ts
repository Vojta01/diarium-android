/**
 * Minimal FCM HTTP v1 sender — no firebase-admin dependency.
 * Exchanges the Firebase service-account for an OAuth2 token (RS256 JWT),
 * then posts a message to the FCM v1 API.
 *
 * Requires Vercel env: FIREBASE_SERVICE_ACCOUNT (JSON string of the
 * service-account key downloaded from Firebase console).
 */

let _accessToken: string | null = null;
let _tokenExpiry = 0;

function base64url(data: Buffer | string): string {
  return Buffer.from(data).toString("base64url");
}

/** Sign a JWT with the service account's private key (RS256). */
function signJwt(header: object, payload: object, privateKey: string): string {
  const crypto = require("crypto") as typeof import("crypto");
  const h = base64url(JSON.stringify(header));
  const p = base64url(JSON.stringify(payload));
  const sig = crypto.createSign("RSA-SHA256").update(`${h}.${p}`).sign(privateKey);
  return `${h}.${p}.${base64url(sig)}`;
}

function getServiceAccount(): any | null {
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!raw) return null;
  try {
    return typeof raw === "string" ? JSON.parse(raw) : raw;
  } catch {
    return null;
  }
}

async function getAccessToken(): Promise<string | null> {
  const sa = getServiceAccount();
  if (!sa || !sa.client_email || !sa.private_key) return null;
  if (_accessToken && Date.now() < _tokenExpiry - 60_000) return _accessToken;

  const now = Math.floor(Date.now() / 1000);
  const jwt = signJwt(
    { alg: "RS256", typ: "JWT" },
    {
      iss: sa.client_email,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: sa.token_uri,
      iat: now,
      exp: now + 3600,
    },
    sa.private_key
  );

  const res = await fetch(sa.token_uri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  if (!res.ok) return null;
  const data = await res.json();
  _accessToken = data.access_token;
  _tokenExpiry = Date.now() + data.expires_in * 1000;
  return _accessToken;
}

export interface FcmMessage {
  title: string;
  body: string;
  data?: Record<string, string>;
}

/** Send a native push to one FCM token. Returns success + http status. */
export async function sendFcm(
  token: string,
  message: FcmMessage
): Promise<{ ok: boolean; status?: number; error?: string }> {
  try {
    const accessToken = await getAccessToken();
    if (!accessToken) return { ok: false, error: "no service account" };
    const sa = getServiceAccount();
    if (!sa) return { ok: false, error: "no service account" };

    const payload = {
      message: {
        token,
        notification: { title: message.title, body: message.body },
        data: message.data ?? {},
        android: { priority: "high" },
      },
    };

    const res = await fetch(
      `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
      }
    );
    if (res.ok) return { ok: true, status: res.status };
    const body = await res.text().catch(() => "");
    return { ok: false, status: res.status, error: body.slice(0, 200) };
  } catch (e: any) {
    return { ok: false, error: String(e?.message || e) };
  }
}

/** Delete a token from FCM after receiving 404/UNREGISTERED. */
export async function unsubscribeFcmToken(token: string): Promise<boolean> {
  try {
    const url = await fcmTokenInfoUrl();
    if (!url) return false;
    const res = await fetch(url, { method: "DELETE" });
    return res.ok;
  } catch {
    return false;
  }
}

async function fcmTokenInfoUrl(): Promise<string | null> {
  const sa = getServiceAccount();
  const accessToken = await getAccessToken();
  if (!sa || !accessToken) return null;
  return `https://firebase.googleapis.com/v1/projects/${sa.project_id}/instances/UNUSED`;
}