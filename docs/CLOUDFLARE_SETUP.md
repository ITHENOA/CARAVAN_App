# Cloudflare Setup for Caravan

## 1. Create a free Cloudflare account

1. Go to https://dash.cloudflare.com/sign-up
2. Verify your email.

## 2. Install Node.js

Install Node.js 20+ from https://nodejs.org/

```powershell
node --version
npm --version
```

## 3. Install backend dependencies

```powershell
cd backend
npm install --legacy-peer-deps
npm install-scripts approve esbuild workerd
npm rebuild
```

## 4. Login to Cloudflare

```powershell
cd backend
npx wrangler login
```

A browser window opens. Authorize Wrangler.

## 5. Generate Cloudflare types

```powershell
cd backend
npm run cf-typegen
```

## 6. Run locally

```powershell
cd backend
npm run dev
```

Local URL is typically:

- `http://127.0.0.1:8787`

Health check:

```powershell
curl http://127.0.0.1:8787/health
```

## 7. Run tests

```powershell
cd backend
npm test
npm run typecheck
```

## 8. Deploy

```powershell
cd backend
npm run deploy
```

## 9. Find your workers.dev URL

After deploy, Wrangler prints a URL like:

`https://caravan-backend.<your-subdomain>.workers.dev`

Also visible in Cloudflare Dashboard → Workers & Pages → your worker → Domains & Routes.

## 10. Configure Flutter

Edit `mobile/lib/core/config/app_config.dart` (or use `--dart-define`):

```text
API_BASE_URL=https://caravan-backend.<your-subdomain>.workers.dev
WS_BASE_URL=wss://caravan-backend.<your-subdomain>.workers.dev
```

Android emulator localhost mapping for local wrangler:

```text
API_BASE_URL=http://10.0.2.2:8787
WS_BASE_URL=ws://10.0.2.2:8787
```

Physical device on same LAN: use your PC LAN IP instead of `10.0.2.2`.

## 11. Redeploy

```powershell
cd backend
npm run deploy
```

## 12. Inspect logs

```powershell
cd backend
npx wrangler tail
```

Or Dashboard → Workers → Logs.

## 13. Secrets

This MVP does not require Worker secrets. If you add any later:

```powershell
npx wrangler secret put SECRET_NAME
```

Never commit secrets to git or Flutter source.

## Durable Object binding

Configured in `backend/wrangler.jsonc`:

- Binding: `TRIPS`
- Class: `TripRoom`
- Migration tag: `v1` (`new_sqlite_classes`)
