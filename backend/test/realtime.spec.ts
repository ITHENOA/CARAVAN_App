import { env } from "cloudflare:workers";
import {
  createExecutionContext,
  waitOnExecutionContext,
} from "cloudflare:test";
import { describe, expect, it } from "vitest";
import worker from "../src/index";

const IncomingRequest = Request;

async function createTrip() {
  const request = new IncomingRequest("http://localhost/api/trips", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      name: "Test Trip",
      displayName: "Leader",
      clientId: "leader-client",
    }),
  });
  const ctx = createExecutionContext();
  const res = await worker.fetch(request, env, ctx);
  await waitOnExecutionContext(ctx);
  expect(res.status).toBe(200);
  return res.json() as Promise<{
    tripId: string;
    inviteCode: string;
    leaderToken: string;
    leaderId: string;
  }>;
}

function wsUrl(tripId: string): string {
  return `http://localhost/trip/${tripId}`;
}

async function openSocket(tripId: string): Promise<WebSocket> {
  const request = new IncomingRequest(wsUrl(tripId), {
    headers: { Upgrade: "websocket" },
  });
  const ctx = createExecutionContext();
  const res = await worker.fetch(request, env, ctx);
  await waitOnExecutionContext(ctx);
  expect(res.status).toBe(101);
  const ws = res.webSocket;
  expect(ws).toBeTruthy();
  ws!.accept();
  return ws!;
}

function waitForMessage(
  ws: WebSocket,
  predicate: (data: Record<string, unknown>) => boolean,
  timeoutMs = 5000,
): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("timeout waiting for message")), timeoutMs);
    const onMessage = (event: MessageEvent) => {
      try {
        const data = JSON.parse(String(event.data)) as Record<string, unknown>;
        if (predicate(data)) {
          clearTimeout(timer);
          ws.removeEventListener("message", onMessage);
          resolve(data);
        }
      } catch {
        // ignore
      }
    };
    ws.addEventListener("message", onMessage);
  });
}

describe("HTTP API", () => {
  it("health check", async () => {
    const request = new IncomingRequest("http://localhost/health");
    const ctx = createExecutionContext();
    const res = await worker.fetch(request, env, ctx);
    await waitOnExecutionContext(ctx);
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toMatchObject({ status: "healthy" });
  });

  it("creates a trip", async () => {
    const trip = await createTrip();
    expect(trip.tripId).toBeTruthy();
    expect(trip.inviteCode).toMatch(/^[A-Z0-9]{3}-[A-Z0-9]{3}$/);
    expect(trip.leaderToken.length).toBeGreaterThan(20);
  });
});

describe("realtime room", () => {
  it("two clients join, share locations, destination, disconnect", async () => {
    const trip = await createTrip();

    const a = await openSocket(trip.tripId);
    const b = await openSocket(trip.tripId);

    a.send(
      JSON.stringify({
        type: "join",
        version: 1,
        timestamp: Date.now(),
        clientId: "client-a",
        displayName: "Ali",
        inviteCode: trip.inviteCode,
        leaderToken: trip.leaderToken,
      }),
    );

    const joinedA = await waitForMessage(a, (m) => m.type === "joined");
    expect(joinedA.clientId).toBe("client-a");
    expect(joinedA.leaderId).toBeTruthy();

    b.send(
      JSON.stringify({
        type: "join",
        version: 1,
        timestamp: Date.now(),
        clientId: "client-b",
        displayName: "Sara",
        inviteCode: trip.inviteCode,
      }),
    );

    const joinedB = await waitForMessage(b, (m) => m.type === "joined");
    expect(joinedB.clientId).toBe("client-b");
    const membersB = joinedB.members as Array<{ id: string }>;
    expect(membersB.some((m) => m.id === "client-a")).toBe(true);

    await waitForMessage(a, (m) => m.type === "member_joined");

    a.send(
      JSON.stringify({
        type: "location_update",
        version: 1,
        timestamp: Date.now(),
        latitude: 35.6892,
        longitude: 51.389,
        speed: 10,
        heading: 90,
      }),
    );

    const locOnB = await waitForMessage(
      b,
      (m) => m.type === "location_update" && m.clientId === "client-a",
    );
    expect(locOnB.latitude).toBe(35.6892);

    b.send(
      JSON.stringify({
        type: "location_update",
        version: 1,
        timestamp: Date.now(),
        latitude: 35.7,
        longitude: 51.4,
      }),
    );
    const locOnA = await waitForMessage(
      a,
      (m) => m.type === "location_update" && m.clientId === "client-b",
    );
    expect(locOnA.longitude).toBe(51.4);

    a.send(
      JSON.stringify({
        type: "destination_update",
        version: 1,
        timestamp: Date.now(),
        latitude: 35.75,
        longitude: 51.41,
        label: "North Gate",
        leaderToken: trip.leaderToken,
      }),
    );

    const destB = await waitForMessage(b, (m) => m.type === "destination_update");
    expect((destB.destination as { label: string }).label).toBe("North Gate");

    // Non-leader cannot update destination
    b.send(
      JSON.stringify({
        type: "destination_update",
        version: 1,
        timestamp: Date.now(),
        latitude: 1,
        longitude: 2,
        leaderToken: "wrong",
      }),
    );
    const err = await waitForMessage(b, (m) => m.type === "error");
    expect(err.code).toBe("UNAUTHORIZED");

    // Malformed JSON
    a.send("{not-json");
    const bad = await waitForMessage(a, (m) => m.type === "error");
    expect(bad.code).toBe("INVALID_MESSAGE");

    // Unknown type
    a.send(JSON.stringify({ type: "explode", version: 1, timestamp: 1 }));
    const unknown = await waitForMessage(a, (m) => m.type === "error");
    expect(unknown.code).toBe("INVALID_MESSAGE");

    b.close(1000, "bye");
    const left = await waitForMessage(a, (m) => m.type === "member_left");
    expect(left.clientId).toBe("client-b");

    // Reconnect B and get snapshot with destination
    const b2 = await openSocket(trip.tripId);
    b2.send(
      JSON.stringify({
        type: "join",
        version: 1,
        timestamp: Date.now(),
        clientId: "client-b",
        displayName: "Sara",
        inviteCode: trip.inviteCode,
      }),
    );
    const rejoined = await waitForMessage(b2, (m) => m.type === "joined");
    expect(rejoined.destination).toBeTruthy();

    a.close(1000, "done");
    b2.close(1000, "done");
  });

  it("isolates different trips", async () => {
    const t1 = await createTrip();
    const t2 = await createTrip();
    expect(t1.tripId).not.toBe(t2.tripId);

    const a = await openSocket(t1.tripId);
    const b = await openSocket(t2.tripId);

    a.send(
      JSON.stringify({
        type: "join",
        version: 1,
        timestamp: Date.now(),
        clientId: "iso-a",
        displayName: "A",
        inviteCode: t1.inviteCode,
        leaderToken: t1.leaderToken,
      }),
    );
    await waitForMessage(a, (m) => m.type === "joined");

    b.send(
      JSON.stringify({
        type: "join",
        version: 1,
        timestamp: Date.now(),
        clientId: "iso-b",
        displayName: "B",
        inviteCode: t2.inviteCode,
        leaderToken: t2.leaderToken,
      }),
    );
    await waitForMessage(b, (m) => m.type === "joined");

    let leaked = false;
    b.addEventListener("message", (event) => {
      const data = JSON.parse(String(event.data)) as { clientId?: string };
      if (data.clientId === "iso-a") leaked = true;
    });

    a.send(
      JSON.stringify({
        type: "location_update",
        version: 1,
        timestamp: Date.now(),
        latitude: 10,
        longitude: 10,
      }),
    );

    await new Promise((r) => setTimeout(r, 200));
    expect(leaked).toBe(false);
    a.close();
    b.close();
  });

  it("PTT floor control", async () => {
    const trip = await createTrip();
    const a = await openSocket(trip.tripId);
    const b = await openSocket(trip.tripId);

    a.send(
      JSON.stringify({
        type: "join",
        version: 1,
        timestamp: Date.now(),
        clientId: "ptt-a",
        displayName: "Ali",
        inviteCode: trip.inviteCode,
        leaderToken: trip.leaderToken,
      }),
    );
    await waitForMessage(a, (m) => m.type === "joined");

    b.send(
      JSON.stringify({
        type: "join",
        version: 1,
        timestamp: Date.now(),
        clientId: "ptt-b",
        displayName: "Sara",
        inviteCode: trip.inviteCode,
      }),
    );
    await waitForMessage(b, (m) => m.type === "joined");

    a.send(JSON.stringify({ type: "ptt_request", version: 1, timestamp: Date.now() }));
    const granted = await waitForMessage(b, (m) => m.type === "ptt_granted");
    expect(granted.clientId).toBe("ptt-a");

    b.send(JSON.stringify({ type: "ptt_request", version: 1, timestamp: Date.now() }));
    const busy = await waitForMessage(b, (m) => m.type === "ptt_busy");
    expect(busy.activeSpeakerId).toBe("ptt-a");

    a.send(JSON.stringify({ type: "ptt_release", version: 1, timestamp: Date.now() }));
    await waitForMessage(b, (m) => m.type === "ptt_release");

    a.close();
    b.close();
  });
});
