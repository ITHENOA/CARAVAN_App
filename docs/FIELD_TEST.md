# Field Test Guide

## Prerequisites

- Backend deployed or `npm run dev` reachable from phones
- 2–4 Android phones on cellular or Wi-Fi
- App installed (debug APK)
- Location + microphone permissions granted

## Phone A — Create trip

1. Complete onboarding (name / car).
2. Tap **Create Trip**.
3. Note invite code and QR.
4. Confirm connection chip shows **Connected**.
5. Confirm self marker on map.

## Phones B/C — Join

1. Onboarding with distinct names.
2. Join with code (or scan QR).
3. Expect member markers for everyone.
4. Move physically; others should see updates within a few seconds.

## Destination

1. On Phone A (leader), long-press map to set destination.
2. All phones should receive destination marker and a “destination changed” notice.

## Temporary offline

1. On Phone B enable Airplane mode for 20–30s.
2. Expect **Offline** / **Reconnecting** on B; A should mark B stale.
3. Disable Airplane mode.
4. Expect automatic rejoin + snapshot (members + destination).

## PTT

1. Hold PTT on A — others see “A is talking” and hear audio if WebRTC connects.
2. While A holds, B request should show busy.
3. Release A; B can then talk.
4. If voice fails (NAT), confirm non-voice features still work. See `WEBRTC.md`.

## Optional

- Bluetooth headset talk/listen
- Screen lock while sharing location (foreground service / OS limits may apply)
- Background location: Android may throttle; document observed behavior

## Expected outcomes

| Scenario | Expectation |
| --- | --- |
| Join | Mutual member lists |
| Move | Live markers, distance labels |
| Leader destination | Synchronized on all |
| Airplane mode | Stale + reconnect recovery |
| PTT | Single floor, UI speaker indicator |
| Voice NAT fail | Clear voice-unavailable status; rest OK |
