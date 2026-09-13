# Caravan

Temporary product name (replace from one config location in the Flutter app: `AppConstants.productName`).

**Caravan** helps groups traveling in multiple cars share live locations, a common destination, and Push-To-Talk voice — without Firebase, paid map SDKs, or a conventional VPS.

## Architecture

| Piece | Tech |
| --- | --- |
| Mobile | Flutter (Android-first) |
| Backend | Cloudflare Workers + Durable Objects + WebSocket Hibernation |
| Map | MapLibre + configurable open style (OpenFreeMap for development) |
| Voice | WebRTC mesh PTT (Worker is signaling only) |
| Transport | Typed JSON protocol v1 over WebSocket |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Repository layout

```
mobile/     Flutter application
backend/    Cloudflare Worker
docs/       Architecture, protocol, setup, field tests
scripts/    Local integration helpers
```

## Requirements

- Node.js 20+
- Flutter 3.29+ / Dart 3.7+ (for MapLibre)
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

## Quick start — Flutter

```powershell
cd mobile
flutter pub get
flutter analyze
flutter test
flutter run
```

Configure API/WS URLs in `mobile/lib/core/config/app_config.dart` or via `--dart-define`.

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

Put the workers.dev URL into Flutter config.

## Testing

```powershell
cd backend
npm test
npm run typecheck

cd ../mobile
flutter analyze
flutter test
```

## APK build

```powershell
cd mobile
flutter build apk --debug
```

Debug APK path (typical):

`mobile/build/app/outputs/flutter-apk/app-debug.apk`

Release requires your own signing config:

```powershell
flutter build apk --release
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
