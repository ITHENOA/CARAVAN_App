# Caravan — Complete Agent Handoff

**Purpose:** Give another coding agent everything needed to understand, run, extend, and ship this app without rediscovering tribal knowledge.

**Repo root:** `carnaval-app` (folder name)  
**Product name:** `Caravan` — change only in `mobile/lib/core/constants/app_constants.dart` → `AppConstants.productName`  
**Date of this handoff:** 2026-09-12

---

## 1. What this product is

Caravan is a **multi-car convoy companion**:

- Create / join a trip with a short invite code (+ QR)
- Live shared map (MapLibre) with member markers
- Shared destination (leader sets it)
- Push-To-Talk voice over WebRTC mesh
- EN + FA localization with RTL

**Hard constraints (product intent):**

- Free / open stack only — no Firebase, no paid map SDKs, no conventional always-on VPS as the primary backend
- Backend = Cloudflare Workers + Durable Objects
- Maps = MapLibre + configurable open style URL
- Voice = peer-to-peer WebRTC; Worker is **signaling only**

---

## 2. Repository layout

```
carnaval-app/
  mobile/          Flutter app (Android-first)
  backend/         Cloudflare Worker + Durable Objects
  docs/            Architecture, protocol, setup, field tests, this handoff
  scripts/         Local integration helpers (e.g. dev_ws_test.mjs)
  graphify-out/    Code knowledge graph (optional for agents)
  README.md
  LICENSE          MIT
  AGENTS.md        Cloudflare Workers agent rules for this repo
```

Ignore / do not treat as product source unless asked:

- `flutter/`, `flutter_application_1/` — leftover scaffolds
- Root Cloudflare scaffold leftovers outside `backend/` if present

---

## 3. High-level architecture

```
Flutter app
  ├─ HTTP  → POST /api/trips, POST /api/trips/lookup
  ├─ WS    → /trip/{tripId}  (JSON protocol v1)
  ├─ MapLibre tiles/style (OpenFreeMap by default)
  └─ WebRTC mesh (audio) ←→ peers
         ↑ signaling only via WS

Cloudflare Worker (backend/src/index.ts)
  ├─ TripRoom DO   — one DO per trip (state + hibernatable WS)
  └─ InviteBook DO — invite code → tripId index
```

Read next:

| Doc | Contents |
| --- | --- |
| `docs/ARCHITECTURE.md` | System design |
| `docs/PROTOCOL.md` | Full WS message schemas |
| `docs/CLOUDFLARE_SETUP.md` | Deploy / bindings |
| `docs/WEBRTC.md` | STUN/TURN, PTT notes |
| `docs/FIELD_TEST.md` | Manual QA checklist |
| `docs/IMPLEMENTATION_REPORT.md` | Earlier status (partially outdated — see §11) |

**Cloudflare rule:** Before changing Workers/DO APIs or limits, fetch current docs (`AGENTS.md` points to developers.cloudflare.com). Prefer Durable Objects best practices.

---

## 4. Backend (`backend/`)

### Stack

- TypeScript, Wrangler 4.x, Vitest + `@cloudflare/vitest-plugin`
- Durable Objects with **WebSocket Hibernation**
- Compatibility: `nodejs_compat` (see `wrangler.jsonc`)

### Key files

| File | Role |
| --- | --- |
| `src/index.ts` | HTTP routes + WS proxy to DO |
| `src/trip-room.ts` | TripRoom DO — join, location, destination, PTT, signaling |
| `src/invite-book.ts` | InviteBook DO — code lookup |
| `src/types.ts` | Message & state types |
| `src/validation.ts` | `parseClientMessage` |
| `src/utils.ts` | IDs, invite codes, hashing, responses |
| `src/env.ts` | Env bindings |
| `wrangler.jsonc` | Worker name, DO bindings, migrations |
| `test/unit.spec.ts`, `test/realtime.spec.ts` | Tests |

### HTTP API

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/` | Service info |
| `GET` | `/health` | Health |
| `POST` | `/api/trips` | Create trip → `{ tripId, inviteCode, leaderToken, ... }` |
| `POST` | `/api/trips/lookup` | Invite code → tripId |
| `GET` | `/api/trips/{id}?meta=1` | Trip metadata |
| Upgrade | `/trip/{tripId}` | WebSocket to TripRoom |

CORS is open (`*`) for mobile/dev convenience.

### Security model

- Clients do **not** self-claim leadership via `isLeader: true`
- Leader actions require `leaderToken`; server stores/verifies **hash**
- Invite codes normalized; payload size limit **32 KiB**
- Basic per-connection rate limiting
- Display names sanitized

### Protocol (summary)

Every frame: `{ type, version: 1, timestamp }` (ms epoch).

**Client → server:** `join`, `location_update`, `destination_update`, `ptt_request`, `ptt_release`, `webrtc_offer`, `webrtc_answer`, `ice_candidate`, `ping`, `leave`

**Server → client:** `joined`, `member_joined`, `member_left`, `members_snapshot`, relayed `location_update`, `destination_update`, `leader_change`, `ptt_granted` / `ptt_busy` / `ptt_release`, signaling mirrors, `pong`, `error`

Full schemas: `docs/PROTOCOL.md`.

### Backend commands

```powershell
cd backend
npm install --legacy-peer-deps
npm install-scripts approve esbuild workerd   # if needed on this machine
npm rebuild
npm test
npm run typecheck
npm run cf-typegen     # after wrangler.jsonc binding changes
npm run dev            # default http://127.0.0.1:8787
```

**LAN / phone hotspot:** bind all interfaces:

```powershell
npx wrangler dev --ip 0.0.0.0 --port 8787
```

**Deploy:**

```powershell
npx wrangler login
npm run deploy
```

**Local A/B WS smoke test** (with `npm run dev` up):

```powershell
cd backend
npm install ws --no-save
node ../scripts/dev_ws_test.mjs http://127.0.0.1:8787
```

---

## 5. Mobile (`mobile/`)

### Stack

- Flutter 3.29+ / Dart 3.7+ (this machine used Flutter **3.47.2** / Dart **3.13.2**)
- Riverpod, go_router, MapLibre (`maplibre_gl`)
- `geolocator`, `flutter_webrtc`, `qr_flutter`, `mobile_scanner`
- `flutter_localizations` EN/FA + RTL
- Config via `--dart-define` → `AppConfig`

### Feature layout (`mobile/lib/`)

```
app/           MaterialApp, router, bootstrap
core/          config, theme, networking, permissions, constants, geo
features/
  onboarding/  name / car / color profile
  home/        hub
  trip/        create, join, trip screen, TripController, protocol models
  map/         TripMapView, mock fleet
  location/    GPS publish / throttle
  destination/ (used via trip/map flows)
  members/     member domain models
  voice/       PTT + WebRTC mesh
  settings/    language/theme/units + debug screen
l10n/          generated + ARB
```

### Important config

File: `mobile/lib/core/config/app_config.dart`

| Define | Purpose | Current default in repo |
| --- | --- | --- |
| `API_BASE_URL` | HTTP API | `https://caravan-backend.ithenoa.workers.dev` |
| `WS_BASE_URL` | WebSocket | `wss://caravan-backend.ithenoa.workers.dev` |
| `MAP_STYLE_URL` | MapLibre style | OpenFreeMap Liberty |
| `ROUTING_BASE_URL` | Optional OSRM-style | empty |
| `ICE_SERVERS` | JSON ICE list | Google STUN |
| `MOCK_MODE` | Fake fleet (debug only) | false |

**Local override examples:**

```powershell
# Android emulator → host machine
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8787 --dart-define=WS_BASE_URL=ws://10.0.2.2:8787

# Physical phone on PC Windows Mobile Hotspot
flutter run --dart-define=API_BASE_URL=http://192.168.137.1:8787 --dart-define=WS_BASE_URL=ws://192.168.137.1:8787
```

`dart-define` values are **compile-time**. Changing URLs requires rebuild/reinstall.

### Android Gradle (working toolchain for Flutter 3.47)

These were required to get `flutter build apk --debug` working:

| Setting | Value | File |
| --- | --- | --- |
| Gradle | **9.3.1** | `android/gradle/wrapper/gradle-wrapper.properties` |
| AGP | **9.1.0** | `android/settings.gradle.kts` |
| Kotlin | **2.4.0** | `android/settings.gradle.kts` |
| `android.newDsl` | `false` | `android/gradle.properties` |
| `android.builtInKotlin` | `false` | `android/gradle.properties` |
| Plugin compileSdk force | `36` via `afterEvaluate` | `android/build.gradle.kts` |

`app/build.gradle.kts` uses `kotlin { compilerOptions { jvmTarget ... } }` (not deprecated `android.kotlinOptions`).

**Known warnings (non-blocking today):** some plugins (`flutter_webrtc`, `maplibre_gl`, `mobile_scanner`, `share_plus`) still apply Kotlin Gradle Plugin; Flutter warns about future Built-in Kotlin migration.

**NDK:** Flutter expects `28.2.13676358`. On this machine SDK lives at `C:\Android\Sdk` (no spaces — important; paths under `Hand 5 Team` broke NDK earlier).

### Mobile commands

```powershell
$env:ANDROID_SDK_ROOT="C:\Android\Sdk"
$env:ANDROID_HOME="C:\Android\Sdk"
$env:JAVA_HOME="C:\Users\Hand 5 Team\tools\jdk-17"
$env:Path="C:\flutter\bin;$env:JAVA_HOME\bin;$env:ANDROID_SDK_ROOT\platform-tools;$env:Path"

cd mobile
flutter pub get
flutter gen-l10n
flutter analyze
flutter test
flutter build apk --debug
```

APK output:

`mobile/build/app/outputs/flutter-apk/app-debug.apk`

---

## 6. Networking topologies (field use)

### Emulator

- Backend: `wrangler dev` on PC
- App URLs: `10.0.2.2:8787`

### Phone ↔ PC via Windows Mobile Hotspot (used on this project)

1. PC online via Ethernet
2. Enable PC Mobile Hotspot; phone joins that hotspot
3. Host IP is typically **`192.168.137.1`**
4. Backend: `npx wrangler dev --ip 0.0.0.0 --port 8787`
5. App dart-defines use `192.168.137.1`
6. Turn off VPN (Windscribe/etc.) — it often breaks LAN/hotspot reachability
7. Allow Windows Firewall TCP **8787** if needed

### Same Wi‑Fi LAN

- Use PC’s LAN IPv4 (not VPN adapters)
- Same `--ip 0.0.0.0` + dart-defines with that IP

### Xiaomi / Redmi USB install

`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` means MIUI blocked USB install:

- Enable **Install via USB** / **USB debugging (Security settings)**
- Or copy APK to phone and sideload (allow unknown apps)

---

## 7. User flows to preserve

1. **Onboarding** — display name, car name, avatar color; stable local client UUID
2. **Create trip** — HTTP create → show invite code + QR → WS join as leader with `leaderToken`
3. **Join trip** — lookup code or scan QR → WS join
4. **Live map** — publish GPS (adaptive interval); show members; distances
5. **Destination** — leader long-press map → `destination_update` → all clients update
6. **PTT** — hold → `ptt_request` → `ptt_granted` or `ptt_busy` → WebRTC audio; release → `ptt_release`
7. **Reconnect** — exponential backoff; re-join; snapshot; push last location
8. **Settings** — language EN/FA, theme, units; debug diagnostics

Field checklist: `docs/FIELD_TEST.md`.

---

## 8. Design / product rules for agents

- Do **not** introduce Firebase, Google Maps SDK, Mapbox paid SDK, or paid auth SaaS
- Keep map style URL configurable; do **not** use `tile.openstreetmap.org` as unlimited production tiles
- Routing is optional; failures must not break location sharing (open-in-external-maps fallback)
- PTT may fail on hard NATs without TURN — UI must degrade gracefully; non-voice features must still work
- Prefer simplest working fix (no speculative refactors)
- After substantial code exploration in this repo, prefer `graphify query` / `graphify path` / `graphify explain` before blind grep (workspace rule)
- After modifying code: `graphify update .` (AST-only)

---

## 9. Tests & quality gates

| Suite | Command | Last known |
| --- | --- | --- |
| Backend unit/realtime | `cd backend && npm test` | 14/14 pass |
| Backend types | `npm run typecheck` | pass |
| WS integration script | `node scripts/dev_ws_test.mjs http://127.0.0.1:8787` | pass |
| Flutter analyze | `cd mobile && flutter analyze` | clean / infos only |
| Flutter tests | `flutter test` | 10/10 pass |
| Debug APK | `flutter build apk --debug` | **works** after Gradle/AGP/Kotlin/NDK fixes |

---

## 10. Known limitations (do not “fix” by violating policies)

- STUN-only WebRTC ≠ reliable cellular PTT → need TURN (`docs/WEBRTC.md`)
- Android background location is OS-throttled; app is **foreground-first**
- Cloudflare free-tier Worker/DO limits
- Some plugins lag Flutter Built-in Kotlin migration
- iOS may need `flutter create --platforms=ios` refresh for a polished iOS build

---

## 11. Current status (as of 2026-09-12) — supersedes older report bits

**Done / working:**

- Backend TripRoom + InviteBook + protocol v1 + tests
- Flutter feature app EN/FA, map, trip, voice pipeline, settings
- Debug APK builds on this Windows machine
- Local + hotspot phone install paths documented
- Default `AppConfig` points at deployed Worker `caravan-backend.ithenoa.workers.dev` (override with dart-define for local)

**In progress / friction:**

- Xiaomi USB install may require MIUI “Install via USB”
- Hotspot + VPN conflicts
- `IMPLEMENTATION_REPORT.md` still says APK was blocked — that is **outdated**

**Good next tasks for an agent:**

1. Production signing + release APK
2. TURN credentials wiring + UI for voice-unavailable
3. Polish Built-in Kotlin / AGP migration when plugins catch up
4. Stronger offline cache + reconnect UX
5. Update `IMPLEMENTATION_REPORT.md` to match current APK/toolchain reality
6. iOS build verification
7. End-to-end field test with 2 phones on cellular + TURN

---

## 12. Machine-specific paths (this developer machine)

| Tool | Path |
| --- | --- |
| Flutter | `C:\flutter` |
| JDK 17 | `C:\Users\Hand 5 Team\tools\jdk-17` |
| Android SDK | `C:\Android\Sdk` |
| NDK | `C:\Android\Sdk\ndk\28.2.13676358` |

Another machine: install Flutter stable, JDK 17, Android SDK (platform 36, build-tools, NDK 28.2.x), accept licenses, then mirror Gradle versions in §5.

---

## 13. Suggested agent bootstrap prompt

Copy/paste for a new agent:

```text
You are continuing the Caravan convoy app (Flutter + Cloudflare Workers).
Read docs/AGENT_HANDOFF.md first, then docs/ARCHITECTURE.md and docs/PROTOCOL.md.
Workspace rules: follow AGENTS.md for Cloudflare; use graphify before broad code exploration.
Do not add Firebase or paid map SDKs. Keep protocol v1 compatible unless you version it.
Goal: <describe the task>
```

---

## 14. Quick “does it work?” checklist

1. `cd backend && npm test` → green  
2. `npm run dev` → `/health` OK  
3. `node ../scripts/dev_ws_test.mjs http://127.0.0.1:8787` → PASS  
4. `cd mobile && flutter test` → green  
5. Build/run with correct dart-defines for your network  
6. Two clients: create + join → markers move → destination syncs → PTT floor UI reacts  

If only voice fails, check ICE/TURN; if everything fails, check URL host (VPN / wrong IP / firewall / cleartext HTTP on Android).
