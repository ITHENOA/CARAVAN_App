# Caravan Architecture

## Overview

Caravan is a multi-car convoy companion. Groups share live locations, a common destination, and Push-To-Talk voice over a lightweight Cloudflare Workers backend. The mobile client is Flutter (Android-first, iOS-ready).

## Repository layout

```
caravan/
  mobile/     Flutter application
  backend/    Cloudflare Worker + Durable Objects
  docs/       Architecture, protocol, setup, field tests
  scripts/    Helper scripts for local testing and deploy
  README.md
```

## High-level components

| Layer | Technology | Role |
| --- | --- | --- |
| Mobile UI | Flutter + Material 3 + Riverpod + go_router | Feature screens, localization, driver UX |
| Maps | MapLibre (`maplibre_gl`) + configurable style URL | Live map, markers, route polyline |
| Location | `geolocator` + adaptive throttling | GPS publish / local display |
| Voice | `flutter_webrtc` mesh + PTT floor control | Peer-to-peer audio |
| Transport | WebSocket (WSS) | Join, presence, location, destination, signaling |
| Backend | Cloudflare Worker | HTTP create-trip + WS upgrade proxy |
| Trip state | Durable Object `TripRoom` + hibernation WS | One DO per trip, durable metadata |

## Backend design

- `POST /api/trips` creates a trip with a cryptographically random human-readable invite code (e.g. `7K4-M2P`), trip id, and leader token.
- Each trip maps to exactly one Durable Object via `env.TRIPS.idFromName(tripId)` / `getByName`.
- WebSocket upgrade path: `GET /trip/{tripId}` → DO `fetch` → `ctx.acceptWebSocket(server)`.
- Per-socket metadata uses `serializeAttachment` / `deserializeAttachment` so hibernation does not lose member identity.
- Durable storage holds: trip id, invite code hash/secret, leader id, leader token hash, destination, light member metadata.
- High-frequency GPS updates stay in memory (and socket attachments) and are broadcast; they are not written to storage on every tick.

## Protocol

Typed JSON messages, `version: 1`, with `type` + `timestamp`. See [PROTOCOL.md](./PROTOCOL.md).

Server validates every inbound payload. Malformed messages return `error` and never crash the room.

## Mobile architecture

Feature-based folders under `mobile/lib/`:

- `app/` — MaterialApp, router, bootstrap
- `core/` — config, theme, networking, localization, permissions
- `features/{onboarding,home,trip,map,location,destination,members,voice,settings}/`

State: Riverpod. Navigation: go_router. Config: single `AppConfig` for API/WS/map/routing/ICE.

## Maps & routing (free / open)

- **Style/tiles:** configurable `MAP_STYLE_URL`. Development default: OpenFreeMap / demo vector style (no proprietary paid key).
- **Do not** use `tile.openstreetmap.org` as a production tile backend.
- **Routing:** `RoutingProvider` abstraction. Dev may use a self-hosted or policy-compliant OSRM endpoint. Failure falls back to straight-line destination + “open in external nav”.

## Voice / WebRTC

- Backend owns PTT floor (`ptt_request` / `ptt_granted` / `ptt_busy` / `ptt_release`).
- Audio is peer-to-peer mesh (2–8 cars). Worker is signaling only.
- Configurable ICE (public STUN for dev; optional TURN for production NAT reliability). See [WEBRTC.md](./WEBRTC.md).

## Security model (no paid auth SaaS)

- Installation UUID persisted locally.
- Leader actions require `leaderToken` generated at trip creation; server hashes and verifies.
- Clients cannot self-promote with `isLeader: true`.
- Input sanitization, payload size limits, basic rate limiting per connection.

## Offline behavior

- Cache trip metadata, destination, last member positions locally.
- On disconnect: show stale markers, keep local GPS, auto-reconnect with exponential backoff + jitter.
- On reconnect: rejoin, snapshot, push current location.

## Localization & RTL

- Flutter gen-l10n: `en` + `fa` with full RTL for Persian.
- Runtime language switch in Settings.

## Environments

| Key | Purpose |
| --- | --- |
| `API_BASE_URL` | HTTPS Worker origin |
| `WS_BASE_URL` | WSS Worker origin |
| `MAP_STYLE_URL` | MapLibre style JSON URL |
| `ROUTING_BASE_URL` | Optional OSRM/Valhalla base |
| `ICE_SERVERS` | STUN/TURN JSON |

Product display name is centralized in `AppConstants.productName` for easy rebranding.
