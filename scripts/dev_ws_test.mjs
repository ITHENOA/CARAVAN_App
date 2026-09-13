/**
 * Realtime integration test: Client A + Client B in same trip.
 * Usage:
 *   node scripts/dev_ws_test.mjs [baseUrl]
 * Default baseUrl: http://127.0.0.1:8787
 */
import { randomUUID } from "node:crypto";

const base = process.argv[2] || "http://127.0.0.1:8787";

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

function waitFor(ws, pred, timeoutMs = 8000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error("timeout: " + pred.toString())), timeoutMs);
    const onMsg = (data) => {
      try {
        const msg = JSON.parse(String(data));
        if (pred(msg)) {
          clearTimeout(t);
          ws.off("message", onMsg);
          resolve(msg);
        }
      } catch {
        /* ignore */
      }
    };
    ws.on("message", onMsg);
  });
}

async function main() {
  const health = await fetch(`${base}/health`);
  assert(health.ok, "health failed");

  const createRes = await fetch(`${base}/api/trips`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ name: "Integration Trip", displayName: "LeaderA", clientId: "leader-a" }),
  });
  assert(createRes.ok, "create trip failed");
  const trip = await createRes.json();
  console.log("created", trip.tripId, trip.inviteCode);

  const wsBase = base.replace(/^http/, "ws");
  const { default: WebSocket } = await import("ws");

  const a = new WebSocket(`${wsBase}/trip/${trip.tripId}`);
  const b = new WebSocket(`${wsBase}/trip/${trip.tripId}`);
  await Promise.all([
    new Promise((r) => a.once("open", r)),
    new Promise((r) => b.once("open", r)),
  ]);

  a.send(JSON.stringify({
    type: "join", version: 1, timestamp: Date.now(),
    clientId: "client-a", displayName: "Ali", inviteCode: trip.inviteCode, leaderToken: trip.leaderToken,
  }));
  const joinedA = await waitFor(a, (m) => m.type === "joined");
  assert(joinedA.clientId === "client-a", "A join");

  const aSeesB = waitFor(
    a,
    (m) =>
      (m.type === "member_joined" && m.member?.id === "client-b") ||
      (m.type === "members_snapshot" &&
        Array.isArray(m.members) &&
        m.members.some((x) => x.id === "client-b")),
  );

  b.send(JSON.stringify({
    type: "join", version: 1, timestamp: Date.now(),
    clientId: "client-b", displayName: "Sara", inviteCode: trip.inviteCode,
  }));
  const joinedB = await waitFor(b, (m) => m.type === "joined");
  assert(joinedB.members.some((m) => m.id === "client-a"), "B sees A");
  await aSeesB;

  a.send(JSON.stringify({
    type: "location_update", version: 1, timestamp: Date.now(),
    latitude: 35.6892, longitude: 51.389, speed: 8, heading: 45,
  }));
  const locB = await waitFor(b, (m) => m.type === "location_update" && m.clientId === "client-a");
  assert(locB.latitude === 35.6892, "B got A location");

  b.send(JSON.stringify({
    type: "location_update", version: 1, timestamp: Date.now(),
    latitude: 35.7, longitude: 51.4,
  }));
  const locA = await waitFor(a, (m) => m.type === "location_update" && m.clientId === "client-b");
  assert(locA.longitude === 51.4, "A got B location");

  a.send(JSON.stringify({
    type: "destination_update", version: 1, timestamp: Date.now(),
    latitude: 35.75, longitude: 51.41, label: "Gate", leaderToken: trip.leaderToken,
  }));
  await waitFor(b, (m) => m.type === "destination_update");

  b.close();
  await waitFor(a, (m) => m.type === "member_left" && m.clientId === "client-b");

  const b2 = new WebSocket(`${wsBase}/trip/${trip.tripId}`);
  await new Promise((r) => b2.once("open", r));
  b2.send(JSON.stringify({
    type: "join", version: 1, timestamp: Date.now(),
    clientId: "client-b", displayName: "Sara", inviteCode: trip.inviteCode,
  }));
  const rejoin = await waitFor(b2, (m) => m.type === "joined");
  assert(!!rejoin.destination, "rejoin has destination");

  a.close();
  b2.close();
  console.log("PASS integration A/B location + destination + reconnect");
}

main().catch((err) => {
  console.error("FAIL", err);
  process.exit(1);
});
