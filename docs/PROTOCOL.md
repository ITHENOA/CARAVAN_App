# Caravan Realtime Protocol

Protocol version: **1**

Transport: JSON text frames over WebSocket (`/trip/{tripId}`).

Every message:

```json
{
  "type": "string",
  "version": 1,
  "timestamp": 1710000000000
}
```

`timestamp` is Unix epoch milliseconds.

Payload size limit: **32 KiB** per frame.

---

## Client → Server

### `join`

```json
{
  "type": "join",
  "version": 1,
  "timestamp": 0,
  "clientId": "uuid",
  "displayName": "Ali",
  "carName": "SUV",
  "avatarColor": "#2E7D32",
  "inviteCode": "7K4-M2P",
  "leaderToken": "optional-if-leader"
}
```

### `location_update`

```json
{
  "type": "location_update",
  "version": 1,
  "timestamp": 0,
  "latitude": 35.6892,
  "longitude": 51.3890,
  "accuracy": 8.5,
  "speed": 12.3,
  "heading": 90.0
}
```

### `destination_update`

Leader only.

```json
{
  "type": "destination_update",
  "version": 1,
  "timestamp": 0,
  "latitude": 35.7,
  "longitude": 51.4,
  "label": "Azadi Tower",
  "leaderToken": "secret"
}
```

### `ptt_request` / `ptt_release`

```json
{ "type": "ptt_request", "version": 1, "timestamp": 0 }
```

```json
{ "type": "ptt_release", "version": 1, "timestamp": 0 }
```

### WebRTC signaling

```json
{
  "type": "webrtc_offer",
  "version": 1,
  "timestamp": 0,
  "targetId": "peer-client-id",
  "sdp": "..."
}
```

Same shape for `webrtc_answer`. For ICE:

```json
{
  "type": "ice_candidate",
  "version": 1,
  "timestamp": 0,
  "targetId": "peer-client-id",
  "candidate": { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 }
}
```

### `ping`

```json
{ "type": "ping", "version": 1, "timestamp": 0 }
```

### `leave`

```json
{ "type": "leave", "version": 1, "timestamp": 0 }
```

---

## Server → Client

### `joined`

Sent to the joining client with full snapshot.

```json
{
  "type": "joined",
  "version": 1,
  "timestamp": 0,
  "tripId": "...",
  "clientId": "...",
  "leaderId": "...",
  "members": [],
  "destination": null,
  "activeSpeakerId": null
}
```

### `member_joined` / `member_left` / `members_snapshot`

### `location_update` (relay)

Includes `clientId` of the source member.

### `destination_update`

Includes `updatedBy` display name / id.

### `leader_change`

### `ptt_granted` / `ptt_busy` / `ptt_release`

`ptt_release` may include `clientId` of who released (or timed out).

### `webrtc_offer` / `webrtc_answer` / `ice_candidate`

Forwarded to `targetId` with `fromId`.

### `pong` / `error`

```json
{
  "type": "error",
  "version": 1,
  "timestamp": 0,
  "code": "UNAUTHORIZED",
  "message": "Leader token required"
}
```

---

## Error codes

| Code | Meaning |
| --- | --- |
| `INVALID_MESSAGE` | Malformed / unknown type |
| `UNAUTHORIZED` | Missing/invalid leader token |
| `NOT_JOINED` | Action before successful join |
| `RATE_LIMITED` | Too many messages |
| `INVALID_LOCATION` | Bad coordinates |
| `TRIP_NOT_FOUND` | Unknown trip |
| `DUPLICATE_CLIENT` | clientId already connected |
| `PTT_BUSY` | Floor held by another member |
| `PAYLOAD_TOO_LARGE` | Frame exceeds limit |

---

## Validation rules

- Reject NaN / out-of-range lat/lng.
- Ignore stale `location_update` when `timestamp` < last accepted for that member.
- Sanitize display names (trim, max 40 chars, strip control chars).
- Leader mutations must present matching `leaderToken`.
