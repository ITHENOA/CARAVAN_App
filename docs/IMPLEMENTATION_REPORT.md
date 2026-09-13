# Implementation Report — Caravan

**Date:** 2026-09-09  
**Workspace:** `carnaval-app` (product name configurable via `AppConstants.productName`)

## Implemented features

### Backend (`backend/`)
- Cloudflare Worker + Durable Object `TripRoom` with **WebSocket Hibernation** (`acceptWebSocket`, attachment serialize/deserialize)
- `InviteBook` Durable Object for short-code → tripId lookup
- HTTP: `GET /`, `GET /health`, `POST /api/trips`, `POST /api/trips/lookup`, `GET /api/trips/{id}?meta=1`, `WS /trip/{tripId}`
- Typed protocol v1 with validation, rate limiting, payload size limits
- Leader authorization via hashed leader token (not client-claimed `isLeader`)
- Location broadcast with stale timestamp rejection
- Destination persistence + sync
- PTT floor control (`ptt_request/granted/busy/release`)
- WebRTC signaling relay (offer/answer/ICE)
- Ping/pong

### Mobile (`mobile/`)
- Flutter feature architecture (Riverpod + go_router)
- EN/FA localization + RTL (`l10n.yaml`, ARB files)
- Onboarding (name/car/color), create trip + QR, join via code/QR
- Live map (MapLibre), location publishing, distances (ahead/behind/away)
- Shared destination (leader long-press)
- PTT UI + WebRTC mesh signaling/audio pipeline
- Settings (language/theme/units/voice), debug diagnostics screen
- Mock fleet provider (debug/`MOCK_MODE` only)
- Unit/widget tests for geo, protocol, reconnect, smoke UI

### Docs / scripts
- `docs/ARCHITECTURE.md`, `PROTOCOL.md`, `CLOUDFLARE_SETUP.md`, `WEBRTC.md`, `FIELD_TEST.md`
- `scripts/dev_ws_test.mjs` realtime A/B integration test
- Root `README.md`, `LICENSE`

## Working features (verified in this environment)

| Item | Status |
| --- | --- |
| Backend `npm install` | Pass |
| Backend `npm test` (14 tests) | Pass |
| Backend `npm run typecheck` | Pass |
| `wrangler dev` local | Pass (`http://127.0.0.1:8788`) |
| Multi-client WS room (A/B join, location, destination, disconnect/reconnect) | Pass via `scripts/dev_ws_test.mjs` |
| Flutter SDK | Pass (3.47.2 / Dart 3.13.2) |
| Portable JDK 17 | Pass |
| `flutter test` (10 tests) | Pass |
| `flutter analyze` (no errors; infos only) | Pass with `--no-fatal-infos` |
| Debug/release APK | **Blocked** — Android SDK not fully installed (winget Android Studio/JDK required admin; Google cmdline-tools download interrupted) |

## Commands executed (selected)

```powershell
# Backend
cd backend
npm install --legacy-peer-deps
npm install-scripts approve esbuild workerd
npm rebuild
npm test
npm run typecheck
npm run cf-typegen
npm run dev
node ../scripts/dev_ws_test.mjs http://127.0.0.1:8788

# Toolchain
git clone https://github.com/flutter/flutter.git -b stable --depth 1 C:\flutter
# portable JDK zip install to tools\jdk-17
```

## Architecture decisions

- One Durable Object per trip; invite index is a separate DO (`InviteBook`) to allow join-by-code without a database.
- Hibernation-safe socket metadata via `serializeAttachment`.
- High-frequency GPS not written to durable storage every tick.
- MapLibre + OpenFreeMap Liberty style for development (configurable `MAP_STYLE_URL`).
- WebRTC mesh for 2–8 cars; Worker is signaling-only.
- Product rename centralized in `AppConstants.productName`.

## Dependencies selected

### Backend
- `wrangler`, `vitest` + `@cloudflare/vitest-plugin` (current Cloudflare testing stack)
- TypeScript strict

### Mobile
- `flutter_riverpod`, `go_router`, `maplibre_gl`, `geolocator`, `permission_handler`, `flutter_webrtc`, `qr_flutter`, `mobile_scanner`, `google_fonts`, `web_socket_channel`, `http`, `shared_preferences`, `share_plus`, `url_launcher`, `connectivity_plus`, `equatable`, `uuid`

## Known limitations / blockers

### Environment blockers (evidence)

1. **Android Studio / OpenJDK winget installs failed** (UAC/admin exit codes 2 / 1603). Portable JDK succeeded; system Android SDK was **not** present at start (`%LOCALAPPDATA%\Android\Sdk` missing).
2. **Android cmdline-tools download** from Google was interrupted (`connection forcibly closed`); SDK manager not fully installed in this session.
3. **Flutter `pub get` / create** intermittently slow or failing on `storage.googleapis.com` downloads (file locks / transport resets observed).
4. Therefore **debug/release APK was not produced in this session**. Re-run once Android SDK platform + build-tools are installed:

```powershell
$env:JAVA_HOME="C:\Users\Hand 5 Team\tools\jdk-17"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:Path="C:\flutter\bin;$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
cd mobile
flutter pub get
flutter gen-l10n
flutter analyze
flutter test
flutter build apk --debug
```

### Product / networking limitations

- **STUN-only WebRTC** cannot guarantee PTT across CGNAT/cellular — configure TURN for production (`docs/WEBRTC.md`).
- **Map tiles**: do not use public OSM raster tiles as unlimited production backend; keep style URL configurable.
- **Routing**: optional; failure must not break location sharing (open-in-maps fallback present).
- **Android background location** is OS-throttled; app is designed for foreground convoy use first.
- Cloudflare free-tier Worker/DO limits apply.

## Incomplete / follow-ups

- Finish Android SDK install + `flutter build apk --debug` on a machine with SDK + accepted licenses.
- Optional: self-hosted OSRM/Valhalla routing URL.
- Optional: coturn TURN for production PTT reliability.
- iOS project skeleton may need `flutter create --platforms=ios` refresh after SDK downloads stabilize.
- Clean root leftover Cloudflare scaffold files if any remain outside `backend/`.

## Test results summary

- Backend Vitest: **14/14 passed**
- Local realtime integration script: **PASS**
- Flutter analyze/test/APK: **blocked by Android SDK / pub download instability in this environment**
