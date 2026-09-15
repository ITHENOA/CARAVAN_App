/**
 * FCM HTTP v1 sender for Cloudflare Workers (Web Crypto, no Firebase Admin SDK).
 * Requires env.FCM_SERVICE_ACCOUNT_JSON = full service-account JSON string.
 */

export interface FcmServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

export interface FcmPushPayload {
  title: string;
  body: string;
  data?: Record<string, string>;
}

let cachedToken: { value: string; expiresAtMs: number } | null = null;
let cachedKey: { pem: string; cryptoKey: CryptoKey } | null = null;

function parseServiceAccount(raw: string | undefined): FcmServiceAccount | null {
  if (!raw?.trim()) return null;
  try {
    const parsed = JSON.parse(raw) as FcmServiceAccount;
    if (!parsed.project_id || !parsed.client_email || !parsed.private_key) return null;
    return parsed;
  } catch {
    return null;
  }
}

function base64UrlEncode(data: ArrayBuffer | Uint8Array | string): string {
  let bytes: Uint8Array;
  if (typeof data === "string") {
    bytes = new TextEncoder().encode(data);
  } else if (data instanceof Uint8Array) {
    bytes = data;
  } else {
    bytes = new Uint8Array(data);
  }
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]!);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const b64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  if (cachedKey?.pem === pem) return cachedKey.cryptoKey;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(pem),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  cachedKey = { pem, cryptoKey: key };
  return key;
}

async function getAccessToken(account: FcmServiceAccount): Promise<string | null> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.expiresAtMs > Date.now() + 60_000) {
    return cachedToken.value;
  }

  const header = base64UrlEncode(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = base64UrlEncode(
    JSON.stringify({
      iss: account.client_email,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    }),
  );
  const unsigned = `${header}.${claim}`;
  const key = await importPrivateKey(account.private_key);
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  const jwt = `${unsigned}.${base64UrlEncode(signature)}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  if (!res.ok) {
    console.warn("[fcm] oauth failed", res.status, await res.text().catch(() => ""));
    return null;
  }
  const json = (await res.json()) as { access_token?: string; expires_in?: number };
  if (!json.access_token) return null;
  const expiresIn = typeof json.expires_in === "number" ? json.expires_in : 3600;
  cachedToken = {
    value: json.access_token,
    expiresAtMs: Date.now() + Math.max(60, expiresIn - 120) * 1000,
  };
  return json.access_token;
}

/** Send one data+notification message. Returns false on hard failure; true if accepted or skipped. */
export async function sendFcmToToken(
  envJson: string | undefined,
  token: string,
  payload: FcmPushPayload,
): Promise<{ ok: boolean; dead?: boolean }> {
  const account = parseServiceAccount(envJson);
  if (!account) return { ok: false };
  if (!token.trim()) return { ok: false };

  const accessToken = await getAccessToken(account);
  if (!accessToken) return { ok: false };

  const data: Record<string, string> = {};
  for (const [k, v] of Object.entries(payload.data ?? {})) {
    data[k] = String(v);
  }

  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token,
          notification: {
            title: payload.title,
            body: payload.body,
          },
          data,
          android: {
            priority: "high",
            notification: {
              channel_id: "caravan_presence",
              sound: "default",
            },
          },
        },
      }),
    },
  );

  if (res.ok) return { ok: true };

  let reason = "";
  try {
    const body = (await res.json()) as {
      error?: { status?: string; details?: Array<{ errorCode?: string }> };
    };
    reason =
      body.error?.details?.find((d) => d.errorCode)?.errorCode ??
      body.error?.status ??
      "";
  } catch {
    // ignore
  }
  console.warn("[fcm] send failed", res.status, reason);
  const dead =
    res.status === 404 ||
    reason === "UNREGISTERED" ||
    reason === "INVALID_ARGUMENT" ||
    reason === "NOT_FOUND";
  return { ok: false, dead };
}

export async function sendFcmToTokens(
  envJson: string | undefined,
  tokens: string[],
  payload: FcmPushPayload,
): Promise<string[]> {
  const dead: string[] = [];
  await Promise.all(
    tokens.map(async (token) => {
      const result = await sendFcmToToken(envJson, token, payload);
      if (result.dead) dead.push(token);
    }),
  );
  return dead;
}
