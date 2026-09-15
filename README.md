# Caravan

Temporary product name.

**Caravan** helps groups traveling in multiple cars share live locations, a common destination, and Push-To-Talk voice — without Firebase, paid map SDKs, or a conventional VPS.

## Architecture

| Piece | Tech |
| --- | --- |
| Clients | Kotlin/Android + standalone Web client |
| Backend | Cloudflare Workers + Durable Objects + WebSocket Hibernation |
| Map | MapLibre + configurable open style (OpenFreeMap for development) |
| Voice | WebRTC mesh PTT (Worker is signaling only) |
| Transport | Typed JSON protocol v1 over WebSocket |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Repository layout

```
web/        Standalone browser application
app/        Kotlin/Android application
backend/    Cloudflare Worker
docs/       Architecture, protocol, setup, field tests
scripts/    Local integration helpers
```

## Requirements

- Node.js 20+
- Android SDK (for APK builds)
- Cloudflare account (free tier) for deploy

## Quick start — backend

```powershell
cd backend
npm install --legacy-peer-deps
npm install-scripts approve esbuild workerd
npm rebuild
npm test
npm run dev
```

Health: `http://127.0.0.1:8787/health`

Full Cloudflare steps: [docs/CLOUDFLARE_SETUP.md](docs/CLOUDFLARE_SETUP.md)

## Quick start — Web

```powershell
cd backend
npx wrangler dev
```

The Worker serves the files in `web/` and keeps the API and WebSocket routes on the same origin.

Android emulator → local Worker: `http://10.0.2.2:8787` / `ws://10.0.2.2:8787`

## Local realtime integration

With `npm run dev` running:

```powershell
cd backend
npm install ws --no-save
node ../scripts/dev_ws_test.mjs http://127.0.0.1:8787
```

## Deployment

```powershell
cd backend
npx wrangler login
npm run deploy
```

The deployed workers.dev URL is the browser app URL.

## Testing

```powershell
cd backend
npm test
npm run typecheck

cd ../app
./gradlew assembleDebug
```

## APK build

```powershell
cd app
./gradlew assembleDebug
```

Debug APK path (typical):

`app/build/outputs/apk/debug/app-debug.apk`

Release requires your own signing config:

```powershell
./gradlew assembleRelease
```

## Documentation

- [**Agent handoff (complete)**](docs/AGENT_HANDOFF.md) — give this to another agent
- [Architecture](docs/ARCHITECTURE.md)
- [Protocol](docs/PROTOCOL.md)
- [Cloudflare setup](docs/CLOUDFLARE_SETUP.md)
- [WebRTC / TURN](docs/WEBRTC.md)
- [Field test](docs/FIELD_TEST.md)
- [Implementation report](docs/IMPLEMENTATION_REPORT.md)

## Known limitations

- STUN-only WebRTC does not guarantee PTT on all cellular NATs — configure TURN for production reliability.
- Public OSM raster tiles must not be used as an unlimited production tile backend; keep `MAP_STYLE_URL` configurable and use a compliant provider.
- Development routing (optional OSRM) must respect that provider’s usage policy; routing failure does not break location sharing.
- Android background location is OS-constrained; design for foreground-first convoy use.
- Cloudflare free-tier Durable Object / Worker limits apply.

## License

MIT (see LICENSE).
