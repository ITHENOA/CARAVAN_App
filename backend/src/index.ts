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
    const pathname = url.pathname.replace(/\/+/g, "/").replace(/\/+$/, "") || "/";

    try {
      if (
        request.method === "GET" &&
        (pathname === "/api/status" ||
          pathname === "/status" ||
          pathname === "/api/api/status" ||
          (pathname === "/" &&
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

      if (request.method === "GET" && pathname === "/health") {
        return withCors(jsonResponse({ status: "healthy" }));
      }

      const LATEST_APK_URL = "https://github.com/ITHENOA/CARAVAN_App/releases/latest/download/caravan-release.apk";

      if (
        (request.method === "GET" || request.method === "HEAD") &&
        (pathname === "/download" ||
          pathname === "/download/latest" ||
          pathname === "/caravan-release.apk" ||
          pathname === "/download/caravan-release.apk" ||
          pathname === "/api/download" ||
          pathname === "/api/caravan-release.apk")
      ) {
        return Response.redirect(LATEST_APK_URL, 302);
      }

      if (
        request.method === "GET" &&
        (pathname === "/api/version" ||
          pathname === "/version" ||
          pathname === "/api/api/version")
      ) {
        return withCors(
          jsonResponse({
            versionCode: 23,
            versionName: "3.5.8",
            changelog: "Caravan v3.5.8:\n• Fixed peer convoy marker flickering during map pan, zoom gestures, and 3D driving navigation\n• Continuous real-time marker tracking with robust per-member fallback projection\n• Expanded viewport culling bounds to prevent edge pop-in\n• 100% English UI and localization",
            downloadUrl: LATEST_APK_URL,
            sha256: "086af036c40703e8cb8a6a84042dca2ebb9f687540ff972a5f0875b5fcecfa4e",
            patch: null,
          }),
        );
      }

      if (
        request.method === "POST" &&
        (pathname === "/api/trips" ||
          pathname === "/trips" ||
          pathname === "/api/api/trips" ||
          pathname === "/api/trips/create" ||
          pathname === "/trips/create")
      ) {
        return withCors(await createTrip(request, env, url));
      }

      if (
        request.method === "POST" &&
        (pathname === "/api/trips/lookup" ||
          pathname === "/trips/lookup" ||
          pathname === "/api/api/trips/lookup")
      ) {
        return withCors(await lookupTrip(request, env));
      }

      const tripMeta = pathname.match(/^(?:\/api)?(?:\/api)?\/trips\/([^/]+)$/);
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

      const tripWs = pathname.match(/^(?:\/api)?(?:\/api)?\/trip\/([^/]+)$/);
      if (tripWs) {
        const tripId = decodeURIComponent(tripWs[1]!);
        const stub = env.TRIPS.get(env.TRIPS.idFromName(tripId));
        return stub.fetch(request);
      }

      if (pathname.startsWith("/api/")) {
        return withCors(errorResponse(404, "NOT_FOUND", "API endpoint not found"));
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
