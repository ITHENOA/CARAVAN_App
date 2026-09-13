# WebRTC / Push-To-Talk Notes

## Architecture

Caravan uses a **mesh** of peer-to-peer WebRTC audio connections for small trips (about 2–8 cars).

The Cloudflare Worker / Durable Object is used **only for signaling and PTT floor control**:

- `ptt_request` / `ptt_granted` / `ptt_busy` / `ptt_release`
- `webrtc_offer` / `webrtc_answer` / `ice_candidate`

Raw audio is **not** relayed through Cloudflare.

## STUN vs TURN

Development ICE defaults to a public STUN server (e.g. Google STUN).

**STUN alone cannot guarantee connectivity** across:

- Cellular CGNAT
- Symmetric NAT
- Restrictive enterprise firewalls
- Some carrier APNs

When direct connectivity fails, the UI shows that voice is unavailable. Location sharing, map, and destination sync continue to work.

## Production reliability upgrade

For reliable PTT in the field, configure a TURN server in `AppConfig.iceServers`.

Options (choose one; not bundled by default):

- Self-hosted coturn (recommended free/open path)
- A commercial TURN provider (paid; optional)

Example dart-define shape:

```text
ICE_SERVERS=[{"urls":"stun:stun.l.google.com:19302"},{"urls":"turn:turn.example.com:3478","username":"...","credential":"..."}]
```

## Audio profile

- Mono
- Echo cancellation / noise suppression / AGC when the platform supports them
- Microphone captured only while PTT is granted and held
- Resources released on release, leave-trip, and app dispose
