import { InviteBook } from "./invite-book";
import { TripRoom } from "./trip-room";
import type { Env } from "./env";
import {
  errorResponse,
  generateTripId,
  jsonResponse,
  sanitizeName,
} from "./utils";

export { TripRoom, InviteBook };
export type { Env };

function corsHeaders(): HeadersInit {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET, POST, OPTIONS",
    "access-control-allow-headers": "content-type",
  };
}

function withCors(res: Response): Response {
  const headers = new Headers(res.headers);
  for (const [k, v] of Object.entries(corsHeaders())) {
    headers.set(k, v);
  }
  return new Response(res.body, { status: res.status, headers });
}

export default {
  async fetch(
    request: Request,
    env: Env,
    _ctx: ExecutionContext,
  ): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    const url = new URL(request.url);

    try {
      if (
        request.method === "GET" &&
        (url.pathname === "/api/status" ||
          (url.pathname === "/" &&
            request.headers.get("accept")?.includes("application/json") === true))
      ) {
        return withCors(
          jsonResponse({
            service: "caravan-backend",
            status: "ok",
            version: 1,
          }),
        );
      }

      if (request.method === "GET" && url.pathname === "/health") {
        return withCors(jsonResponse({ status: "healthy" }));
      }

      const LATEST_APK_URL = "https://github.com/ITHENOA/CARAVAN_App/releases/latest/download/caravan-release.apk";

      if (
        request.method === "GET" &&
        (url.pathname === "/download" ||
          url.pathname === "/download/latest" ||
          url.pathname === "/caravan-release.apk" ||
          url.pathname === "/download/caravan-release.apk" ||
          url.pathname === "/api/download" ||
          url.pathname === "/api/caravan-release.apk")
      ) {
        return Response.redirect(LATEST_APK_URL, 302);
      }

      if (request.method === "GET" && url.pathname === "/api/version") {
        return withCors(
          jsonResponse({
            versionCode: 17,
            versionName: "3.5.2",
            changelog: "به‌روزرسانی نسخه ۳.۵.۲ کاروان:\n• رفع مشکل ایجاد سفر جدید و فعال‌سازی مجدد روت سرور Cloudflare\n• رفع نمایش اشتباه نوتیفیکیشن سفر فعال (Leave Convoy) در صورت عدم حضور در سفر\n• اصلاح چرخه حیات سرویس و توقف خودکار هنگام خروج از سفر یا خطای اتصال\n• نمایش پیام‌های خطای واقعی شبکه هنگام ایجاد یا ورود به سفر",
            downloadUrl: LATEST_APK_URL,
            sha256: "b899f3fc1bd4c9d7110e14ef1cba016184a2ebf14e9365d289b51b92aed38d5a",
            patch: null,
          }),
        );
      }

      if (request.method === "POST" && url.pathname === "/api/trips") {
        return withCors(await createTrip(request, env, url));
      }

      if (request.method === "POST" && url.pathname === "/api/trips/lookup") {
        return withCors(await lookupTrip(request, env));
      }

      const tripMeta = url.pathname.match(/^\/api\/trips\/([^/]+)$/);
      if (request.method === "GET" && tripMeta) {
        const tripId = decodeURIComponent(tripMeta[1]!);
        const stub = env.TRIPS.get(env.TRIPS.idFromName(tripId));
        const res = await stub.fetch(
          new Request(`https://trip.internal/trip/${tripId}?meta=1`, {
            method: "GET",
          }),
        );
        return withCors(res);
      }

      const tripWs = url.pathname.match(/^\/trip\/([^/]+)$/);
      if (tripWs) {
        const tripId = decodeURIComponent(tripWs[1]!);
        const stub = env.TRIPS.get(env.TRIPS.idFromName(tripId));
        return stub.fetch(request);
      }

      return env.ASSETS.fetch(request);
    } catch (err) {
      const message = err instanceof Error ? err.message : "Internal error";
      return withCors(errorResponse(500, "INTERNAL", message));
    }
  },
};

async function createTrip(
  request: Request,
  env: Env,
  url: URL,
): Promise<Response> {
  let body: Record<string, unknown> = {};
  try {
    body = (await request.json()) as Record<string, unknown>;
  } catch {
    body = {};
  }

  const tripId = generateTripId();
  const leaderId =
    typeof body.clientId === "string" && body.clientId
      ? String(body.clientId).slice(0, 64)
      : crypto.randomUUID();
  const name =
    sanitizeName(body.name, 60) ||
    sanitizeName(body.displayName, 40) ||
    "Caravan Trip";

  const stub = env.TRIPS.get(env.TRIPS.idFromName(tripId));
  const res = await stub.fetch(
    new Request("https://trip.internal/create", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        tripId,
        leaderId,
        name,
        displayName: body.displayName,
      }),
    }),
  );

  if (!res.ok) {
    return res;
  }

  const data = (await res.json()) as {
    tripId: string;
    inviteCode: string;
    leaderToken: string;
    leaderId: string;
    name: string;
  };

  const invites = env.INVITES.get(env.INVITES.idFromName("global"));
  await invites.fetch(
    new Request("https://invite.internal/put", {
      method: "PUT",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        inviteCode: data.inviteCode,
        tripId: data.tripId,
      }),
    }),
  );

  const origin = `${url.protocol}//${url.host}`;
  const joinUrl = `${origin}/join?tripId=${encodeURIComponent(data.tripId)}&code=${encodeURIComponent(data.inviteCode)}`;
  const qrPayload = `caravan://join/${data.tripId}?code=${encodeURIComponent(data.inviteCode)}`;

  return jsonResponse({
    ...data,
    joinUrl,
    qrPayload,
  });
}

async function lookupTrip(request: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown> = {};
  try {
    body = (await request.json()) as Record<string, unknown>;
  } catch {
    body = {};
  }
  const inviteCode = typeof body.inviteCode === "string" ? body.inviteCode : "";
  if (!inviteCode) {
    return errorResponse(400, "INVALID_MESSAGE", "inviteCode required");
  }
  const invites = env.INVITES.get(env.INVITES.idFromName("global"));
  return invites.fetch(
    new Request(
      `https://invite.internal/lookup?code=${encodeURIComponent(inviteCode)}`,
      { method: "GET" },
    ),
  );
}
