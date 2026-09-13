#!/usr/bin/env node
import WebSocket from 'ws';
import { randomUUID } from 'node:crypto';

const httpBase = process.env.CARAVAN_HTTP ?? 'http://127.0.0.1:8787';
const wsBase = process.env.CARAVAN_WS ?? 'ws://127.0.0.1:8787';
const created = await fetch(`${httpBase}/api/trips`, {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ displayName: 'Driver A', clientId: randomUUID() }),
});
if (!created.ok) throw new Error(`create trip failed: ${created.status} ${await created.text()}`);
const trip = await created.json();

function frame(type, payload = {}) {
  return JSON.stringify({ type, version: 1, timestamp: Date.now(), ...payload });
}

function connect(label, extra = {}) {
  const clientId = randomUUID();
  const ws = new WebSocket(`${wsBase}/trip/${trip.tripId}`);
  ws.on('open', () => ws.send(frame('join', { clientId, displayName: label, inviteCode: trip.inviteCode, ...extra })));
  ws.on('message', (data) => console.log(label, data.toString()));
  return { ws, clientId };
}

const a = connect('Driver A', { leaderToken: trip.leaderToken });
const b = connect('Driver B');
setTimeout(() => {
  a.ws.send(frame('location_update', { latitude: 35.6892, longitude: 51.3890, accuracy: 8 }));
  b.ws.send(frame('location_update', { latitude: 35.6902, longitude: 51.3900, accuracy: 10 }));
  a.ws.send(frame('destination_update', { latitude: 35.7, longitude: 51.4, label: 'Test destination', leaderToken: trip.leaderToken }));
}, 1000);
setTimeout(() => {
  a.ws.send(frame('leave'));
  b.ws.send(frame('leave'));
  a.ws.close();
  b.ws.close();
}, 5000);
