import {
  MAX_CAR_NAME,
  MAX_DISPLAY_NAME,
  MAX_LABEL,
  MAX_PAYLOAD_BYTES,
  PROTOCOL_VERSION,
  type ClientMessage,
  type ErrorCode,
} from "./types";
import {
  byteLengthUtf8,
  isValidLatLng,
  sanitizeName,
} from "./utils";

export class ProtocolError extends Error {
  constructor(
    public code: ErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "ProtocolError";
  }
}

function requireString(obj: Record<string, unknown>, key: string): string {
  const v = obj[key];
  if (typeof v !== "string" || !v.trim()) {
    throw new ProtocolError("INVALID_MESSAGE", `Missing or invalid ${key}`);
  }
  return v;
}

function requireNumber(obj: Record<string, unknown>, key: string): number {
  const v = obj[key];
  if (typeof v !== "number" || !Number.isFinite(v)) {
    throw new ProtocolError("INVALID_MESSAGE", `Missing or invalid ${key}`);
  }
  return v;
}

export function parseClientMessage(raw: string | ArrayBuffer): ClientMessage {
  if (typeof raw !== "string") {
    throw new ProtocolError("INVALID_MESSAGE", "Binary frames are not supported");
  }
  if (byteLengthUtf8(raw) > MAX_PAYLOAD_BYTES) {
    throw new ProtocolError("PAYLOAD_TOO_LARGE", "Payload exceeds 32 KiB");
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new ProtocolError("INVALID_MESSAGE", "Malformed JSON");
  }

  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new ProtocolError("INVALID_MESSAGE", "Message must be an object");
  }

  const obj = parsed as Record<string, unknown>;
  const type = obj.type;
  const version = obj.version;
  const timestamp = obj.timestamp;

  if (typeof type !== "string") {
    throw new ProtocolError("INVALID_MESSAGE", "Missing type");
  }
  if (version !== PROTOCOL_VERSION) {
    throw new ProtocolError("INVALID_MESSAGE", `Unsupported protocol version`);
  }
  if (typeof timestamp !== "number" || !Number.isFinite(timestamp)) {
    throw new ProtocolError("INVALID_MESSAGE", "Invalid timestamp");
  }

  switch (type) {
    case "join": {
      const clientId = requireString(obj, "clientId").slice(0, 64);
      const displayName = sanitizeName(obj.displayName, MAX_DISPLAY_NAME);
      if (!displayName) {
        throw new ProtocolError("INVALID_MESSAGE", "displayName required");
      }
      const inviteCode = requireString(obj, "inviteCode").slice(0, 32);
      const carName = sanitizeName(obj.carName, MAX_CAR_NAME) || undefined;
      const avatarColor =
        typeof obj.avatarColor === "string"
          ? obj.avatarColor.slice(0, 16)
          : undefined;
      const leaderToken =
        typeof obj.leaderToken === "string" ? obj.leaderToken : undefined;
      return {
        type: "join",
        version: PROTOCOL_VERSION,
        timestamp,
        clientId,
        displayName,
        carName,
        avatarColor,
        inviteCode,
        leaderToken,
      };
    }
    case "leave":
    case "ping":
    case "ptt_request":
    case "ptt_release":
      return {
        type,
        version: PROTOCOL_VERSION,
        timestamp,
      } as ClientMessage;
    case "location_update": {
      const latitude = requireNumber(obj, "latitude");
      const longitude = requireNumber(obj, "longitude");
      if (!isValidLatLng(latitude, longitude)) {
        throw new ProtocolError("INVALID_LOCATION", "Invalid coordinates");
      }
      const accuracy =
        typeof obj.accuracy === "number" && Number.isFinite(obj.accuracy)
          ? obj.accuracy
          : undefined;
      const speed =
        typeof obj.speed === "number" && Number.isFinite(obj.speed)
          ? obj.speed
          : undefined;
      const heading =
        typeof obj.heading === "number" && Number.isFinite(obj.heading)
          ? obj.heading
          : undefined;
      return {
        type: "location_update",
        version: PROTOCOL_VERSION,
        timestamp,
        latitude,
        longitude,
        accuracy,
        speed,
        heading,
      };
    }
    case "destination_update": {
      const latitude = requireNumber(obj, "latitude");
      const longitude = requireNumber(obj, "longitude");
      if (!isValidLatLng(latitude, longitude)) {
        throw new ProtocolError("INVALID_LOCATION", "Invalid coordinates");
      }
      const leaderToken = requireString(obj, "leaderToken");
      const label = sanitizeName(obj.label, MAX_LABEL) || undefined;
      return {
        type: "destination_update",
        version: PROTOCOL_VERSION,
        timestamp,
        latitude,
        longitude,
        label,
        leaderToken,
      };
    }
    case "map_mark": {
      const latitude = requireNumber(obj, "latitude");
      const longitude = requireNumber(obj, "longitude");
      if (!isValidLatLng(latitude, longitude)) {
        throw new ProtocolError("INVALID_LOCATION", "Invalid coordinates");
      }
      const color =
        typeof obj.color === "string" ? obj.color.slice(0, 16) : undefined;
      return {
        type: "map_mark",
        version: PROTOCOL_VERSION,
        timestamp,
        latitude,
        longitude,
        color,
      };
    }
    case "map_mark_clear": {
      return {
        type: "map_mark_clear",
        version: PROTOCOL_VERSION,
        timestamp,
      };
    }
    case "route_update": {
      const pointsRaw = obj.points;
      if (!Array.isArray(pointsRaw) || pointsRaw.length < 2) {
        throw new ProtocolError("INVALID_MESSAGE", "Route needs points");
      }
      if (pointsRaw.length > 250) {
        throw new ProtocolError("INVALID_MESSAGE", "Too many route points");
      }
      const points: Array<[number, number]> = [];
      for (const p of pointsRaw) {
        if (!Array.isArray(p) || p.length < 2) {
          throw new ProtocolError("INVALID_MESSAGE", "Invalid route point");
        }
        const lat = Number(p[0]);
        const lng = Number(p[1]);
        if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
          throw new ProtocolError("INVALID_LOCATION", "Invalid route point");
        }
        if (!isValidLatLng(lat, lng)) {
          throw new ProtocolError("INVALID_LOCATION", "Invalid route point");
        }
        points.push([lat, lng]);
      }
      const colorHex =
        typeof obj.colorHex === "string" ? obj.colorHex.slice(0, 16) : undefined;
      const segments: Array<{
        colorHex: string;
        points: Array<[number, number]>;
      }> = [];
      if (Array.isArray(obj.segments)) {
        for (const s of obj.segments.slice(0, 40)) {
          if (!s || typeof s !== "object" || Array.isArray(s)) continue;
          const seg = s as Record<string, unknown>;
          const sp = seg.points;
          if (!Array.isArray(sp) || sp.length < 2) continue;
          const segPts: Array<[number, number]> = [];
          for (const p of sp.slice(0, 80)) {
            if (!Array.isArray(p) || p.length < 2) continue;
            const lat = Number(p[0]);
            const lng = Number(p[1]);
            if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
            if (!isValidLatLng(lat, lng)) continue;
            segPts.push([lat, lng]);
          }
          if (segPts.length < 2) continue;
          segments.push({
            colorHex:
              typeof seg.colorHex === "string"
                ? seg.colorHex.slice(0, 16)
                : "#2563EB",
            points: segPts,
          });
        }
      }
      return {
        type: "route_update",
        version: PROTOCOL_VERSION,
        timestamp,
        colorHex,
        points,
        segments: segments.length > 0 ? segments : undefined,
      };
    }
    case "route_clear": {
      return {
        type: "route_clear",
        version: PROTOCOL_VERSION,
        timestamp,
      };
    }
    case "end_trip": {
      const leaderToken = requireString(obj, "leaderToken");
      return {
        type: "end_trip",
        version: PROTOCOL_VERSION,
        timestamp,
        leaderToken,
      };
    }
    case "webrtc_offer":
    case "webrtc_answer": {
      const targetId = requireString(obj, "targetId").slice(0, 64);
      const sdp = requireString(obj, "sdp");
      if (byteLengthUtf8(sdp) > MAX_PAYLOAD_BYTES / 2) {
        throw new ProtocolError("PAYLOAD_TOO_LARGE", "SDP too large");
      }
      return {
        type,
        version: PROTOCOL_VERSION,
        timestamp,
        targetId,
        sdp,
      };
    }
    case "ice_candidate": {
      const targetId = requireString(obj, "targetId").slice(0, 64);
      const cand = obj.candidate;
      if (!cand || typeof cand !== "object" || Array.isArray(cand)) {
        throw new ProtocolError("INVALID_MESSAGE", "Invalid ICE candidate");
      }
      const c = cand as Record<string, unknown>;
      if (typeof c.candidate !== "string") {
        throw new ProtocolError("INVALID_MESSAGE", "Invalid ICE candidate");
      }
      return {
        type: "ice_candidate",
        version: PROTOCOL_VERSION,
        timestamp,
        targetId,
        candidate: {
          candidate: c.candidate,
          sdpMid: typeof c.sdpMid === "string" ? c.sdpMid : null,
          sdpMLineIndex:
            typeof c.sdpMLineIndex === "number" ? c.sdpMLineIndex : null,
        },
      };
    }
    case "chat_message": {
      const text = requireString(obj, "text").trim().slice(0, 500);
      if (!text) {
        throw new ProtocolError("INVALID_MESSAGE", "Text cannot be empty");
      }
      return {
        type: "chat_message",
        version: PROTOCOL_VERSION,
        timestamp,
        text,
      };
    }
    case "audio_chunk": {
      const data = requireString(obj, "data");
      const sampleRate = typeof obj.sampleRate === "number" ? obj.sampleRate : 16000;
      const seq = typeof obj.seq === "number" ? obj.seq : 0;
      return {
        type: "audio_chunk",
        version: PROTOCOL_VERSION,
        timestamp,
        data,
        sampleRate,
        seq,
      };
    }
    default:
      throw new ProtocolError("INVALID_MESSAGE", `Unknown message type: ${type}`);
  }
}
