# Caravan (کاروان)

<div align="center">

![Caravan Banner](https://raw.githubusercontent.com/ithenoa/caravan/main/art/banner.png)

**Private, Real-Time Convoy Navigation, Group Fleet Tracking & Mesh Push-to-Talk Voice**

**ناوبری کاروانی بلادرنگ، ردیابی کاروان خودرویی و بی‌سیم صوتی Push-To-Talk (PTT)**

[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Web-blue?style=for-the-badge&logo=android)](https://github.com/ITHENOA/CARAVAN_App)
[![Download APK](https://img.shields.io/badge/Download-Latest%20APK-brightgreen?style=for-the-badge&logo=android)](https://github.com/ITHENOA/CARAVAN_App/releases/latest/download/caravan-release.apk)
[![Engine](https://img.shields.io/badge/Backend-Cloudflare%20Workers-orange?style=for-the-badge&logo=cloudflare)](https://workers.cloudflare.com/)
[![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](LICENSE)

### 📲 [Direct Download Latest APK (لینک مستقیم دریافت نسخه جدید)](https://github.com/ITHENOA/CARAVAN_App/releases/latest/download/caravan-release.apk)
**Mirror URL (لینک کلودفلر):** `https://caravan-backend.ithenoa.workers.dev/download`

[English](#english) • [فارسی](#فارسی) • [Direct Download](#direct-download) • [Architecture](#architecture) • [Cloudflare Setup](#cloudflare-deployment) • [Documentation](#documentation)

</div>

---

<a name="english"></a>
## English Overview

**Caravan** is an enterprise-grade, high-performance decentralized travel and convoy coordination system designed for group road trips, off-road expeditions, motorcades, and multi-vehicle fleets. It eliminates reliance on costly proprietary SDKs (such as Google Maps Platform fees or Firebase Realtime DB limits) by orchestrating real-time state with **Cloudflare Workers**, **Durable Objects with WebSocket Hibernation**, and open-source vector map layers via **MapLibre**.

### Key Highlights
- **Live Fleet Tracking**: Low-latency vehicle telemetry sharing with bearing, velocity, and distance calculation without drain on mobile batteries.
- **Smart Convoy Re-Centering**: Smooth spherical interpolation (`slerp`) and dynamic framing bounding-box that continuously adapts to group spread.
- **Voice Intercom (Mesh PTT)**: Ultra-low latency Push-To-Talk walkie-talkie powered by WebRTC and raw PCM streaming with jitter-buffer acoustics.
- **Synchronized Destination & Turn-by-Turn Routing**: Multi-engine routing fallback supporting local engines (Neshan API & OSRM) with live traffic polyline color coding.
- **In-App Smart Delta Updating (BSPatch)**: Differential binary delta updates served over Cloudflare Workers / R2, downloading only changed byte deltas rather than re-downloading entire APK binaries.
- **Offline & Censorship Resilience**: Built-in SOCKS5 proxy support, Termux/Aether helper tunneling, and encrypted DNS presets (Cloudflare, Google, Shecan, Electro) to guarantee connectivity in hostile or restricted network environments.

---

<a name="فارسی"></a>
## معرفی فارسی (Persian Overview)

**کاروان (Caravan)** یک سامانه پیشرفته و بومی جهت مدیریت و هماهنگی هوشمند سفرهای گروهی، تورهای آفرود، کاروان‌های خودرویی و ناوگان‌های چند وسیله‌ای است. این سیستم بدون وابستگی به سرورهای هزینه‌بر سنتی یا تحریم‌های گوگل، زیرساختی کاملاً مستقل و بلادرنگ برای اشتراک موقعیت، مسیریابی مشترک و بی‌سیم صوتی درون‌برنامه‌ای فراهم می‌سازد.

### قابلیت‌های کلیدی
- **ردیابی زنده کاروان خودروها:** نمایش لحظه‌ای سرعت، زاویه حرکت و موقعیت مکانی اعضای کاروان بر روی نقشه وکتور روان با کمترین مصرف باتری و اینترنت.
- **بی‌سیم اینترنتی سریع (Push-To-Talk):** ارتباط صوتی واکی-تاکی لحظه‌ای بدون تأخیر با بافر ضد جیتر و جلوگیری از اکوی صدا در طول رانندگی.
- **مسیریابی مشترک و ترافیک لحظه‌ای:** پشتیبانی از مسیریابی نشان (Neshan) و OSRM همراه با نمایش پلی‌لاین ترافیکی رنگی و تعیین مقاصد مشترک توسط سرگروه.
- **به‌روزرسانی هوشمند درون‌برنامه‌ای (Delta Patching):** ارتقای نسخه اپلیکیشن با دانلود تفاضلی تنها چند مگابایت تغییرات (BSDiff/BSPatch) از طریق کلودفلر، بدون نیاز به دانلود مجدد کل حجم برنامه.
- **دور زدن اختلالات و پایداری شبکه:** مجهز به سامانه‌های تنظیم DNS امن (شکن، الکترو، کلودفلر، گوگل)، پشتیبانی از پروکسی محلی و تانلینگ اختصاصی Aether جهت حفظ اتصال بدون قطعی در شرایط سخت اینترنتی.

---

<a name="direct-download"></a>
## 🚀 Direct Download (دریافت مستقیم اپلیکیشن)

You can always download the latest APK directly using either of the following persistent links:

- **Primary URL (GitHub Releases):**  
  [`https://github.com/ITHENOA/CARAVAN_App/releases/latest/download/caravan-release.apk`](https://github.com/ITHENOA/CARAVAN_App/releases/latest/download/caravan-release.apk)
- **Edge Mirror URL (Cloudflare Worker):**  
  [`https://caravan-backend.ithenoa.workers.dev/download`](https://caravan-backend.ithenoa.workers.dev/download)

---

<a name="architecture"></a>
## Architecture & Technology Stack

| Layer | Technology | Key Details |
| :--- | :--- | :--- |
| **Mobile App (Android)** | Kotlin & Jetpack Compose | Material 3, Coroutines/Flow, MapLibre SDK Native, Foreground Location Service |
| **Web Dashboard** | Vanilla TypeScript & HTML5 | Lightweight web client running in browser without app installation |
| **Real-time Backend** | Cloudflare Workers & Durable Objects | Sub-20ms WebSocket Hibernation, zero idle billing, global edge deployment |
| **Map Rendering** | MapLibre GL Native | Vector map tiles, custom convoy styling, 60fps marker interpolation |
| **Audio Transmission** | WebRTC + PCM Stream Engine | Sub-100ms PTT, AudioTrack hardware buffers, noise suppressor |
| **Delta Patcher** | Pure Kotlin BSDiff / BSPatch | Reconstructs target APKs from differential byte-level patches |

```
                 ┌────────────────────────────────┐
                 │     Cloudflare Edge Network    │
                 │                                │
                 │   [Cloudflare Worker Gateway]  │
                 │         /api/*  &  /trip/*     │
                 └──────────────┬─────────────────┘
                                │ WebSocket Hibernation
                                ▼
                 ┌────────────────────────────────┐
                 │    Durable Objects (Rooms)     │
                 │   • Convoy State Synchronization│
                 │   • WebRTC Signaling (PTT Mesh)│
                 │   • Dynamic Invite Registry    │
                 └───────▲────────────────▲───────┘
                         │                │
            WebSocket PDU│                │WebSocket PDU
                         │                │
        ┌────────────────┴──────┐   ┌─────┴─────────────────┐
        │ Android Lead Vehicle  │   │ Android Convoy Member │
        │ • GPS Foreground Svc  │   │ • Live Map View (60fps│
        │ • Route & Destination │   │ • Mesh PTT Audio      │
        │ • Smart Delta Updater │   │ • Offline Reconnecting│
        └───────────────────────┘   └───────────────────────┘
```

---

## Directory Structure

```
├── app/                    # Production Android application (Kotlin + Jetpack Compose)
│   ├── src/main/java/      # Clean architecture: data, model, network, voice, ui
│   └── build.gradle.kts    # Android build scripts, dependencies & version catalog
├── backend/                # Cloudflare Worker & Durable Object coordination engine
│   ├── src/index.ts        # HTTP router, /api/version, WebSocket upgrade handler
│   ├── src/trip-room.ts    # Durable Object convoy state machine
│   └── wrangler.jsonc      # Cloudflare bindings and Durable Object migrations
├── web/                    # Universal web interface for desktop and non-Android devices
├── docs/                   # Full engineering specifications
│   ├── ARCHITECTURE.md     # In-depth architectural blueprint
│   ├── PROTOCOL.md         # WebSocket JSON typed protocol specification
│   ├── CLOUDFLARE_SETUP.md # Complete deployment handbook
│   └── FIELD_TEST.md       # Real-world convoy drive verification reports
└── scripts/                # Automated testing and integration harnesses
```

---

<a name="cloudflare-deployment"></a>
## Cloudflare Worker Deployment

Caravan's backend runs on **Cloudflare Workers** with Durable Objects, fitting completely within the free tier.

### 1. Prerequisites
- Node.js 20+ installed
- A free [Cloudflare Account](https://dash.cloudflare.com/)

### 2. Deployment Commands
```bash
# Navigate to backend
cd backend

# Install dependencies
npm install

# Authenticate with Cloudflare
npx wrangler login

# Deploy globally to Cloudflare Edge
npm run deploy
```

The resulting URL (e.g., `https://caravan-backend.<your-subdomain>.workers.dev`) is configured in the Android app under **Settings → Server URL**.

---

## Building the Android APK

### Requirements
- Android SDK 35 (API 26 minimum, API 36 target)
- JDK 21

### Assemble Debug or Release APK
```bash
# Debug APK
gradle :app:assembleDebug

# Release APK
gradle :app:assembleRelease
```

Output APK will be generated at:
```
app/build/outputs/apk/release/app-release.apk
```

---

## Smart In-App Updating (Delta Patching)

To ship a lightweight delta patch between versions (e.g. from version `14` to `15`):

```bash
# 1. Generate differential patch using bsdiff
bsdiff caravan-v14.apk caravan-v15.apk caravan-v14-to-v15.patch

# 2. Host caravan-v14-to-v15.patch on Cloudflare R2 or Worker assets
# 3. Update /api/version in backend/src/index.ts
```

The application automatically downloads only the patch, verifies byte integrity, reconstructs the new APK, and invokes Android's `PackageInstaller`.

---

<a name="documentation"></a>
## Engineering Documentation

- [**Full Architecture Blueprint**](docs/ARCHITECTURE.md)
- [**Realtime WebSocket Protocol**](docs/PROTOCOL.md)
- [**Cloudflare Deployment Guide**](docs/CLOUDFLARE_SETUP.md)
- [**WebRTC & Audio Subsystem**](docs/WEBRTC.md)
- [**Convoy Field Test Procedures**](docs/FIELD_TEST.md)
- [**Agent Handoff Specification**](docs/AGENT_HANDOFF.md)

---

## Security & Privacy Notice

Caravan is built privacy-first:
- **No permanent tracking**: Trip rooms and locations hibernate and expire when cars disconnect.
- **Decentralized signaling**: No audio recordings are permanently stored on servers.
- **Zero third-party telemetry**: Free of ad trackers, Google Analytics, or opaque analytics daemons.

---

## Author & License

Designed and engineered by **ITHENOA**.  
Licensed under the [MIT License](LICENSE).
