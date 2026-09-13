/** Shared types for Caravan backend protocol v1 */

export const PROTOCOL_VERSION = 1 as const;
export const MAX_PAYLOAD_BYTES = 32 * 1024;
export const MAX_DISPLAY_NAME = 40;
export const MAX_CAR_NAME = 40;
export const MAX_LABEL = 80;

export type ConnectionStatus = "connected" | "reconnecting" | "offline";

export interface Destination {
  latitude: number;
  longitude: number;
  label?: string;
  updatedAt: number;
  updatedById: string;
  updatedByName: string;
}

/** Personal map pin placed by any member (visible to the whole trip). */
export interface MapMark {
  clientId: string;
  displayName: string;
  latitude: number;
  longitude: number;
  color: string;
  updatedAt: number;
}

export interface MemberPublic {
  id: string;
  displayName: string;
  carName?: string;
  avatarColor?: string;
  latitude?: number;
  longitude?: number;
  accuracy?: number;
  speed?: number;
  heading?: number;
  lastLocationAt?: number;
  lastSeenAt: number;
  connectionStatus: ConnectionStatus;
  isLeader: boolean;
}

export interface TripPersistedState {
  tripId: string;
  inviteCode: string;
  inviteCodeNormalized: string;
  leaderId: string;
  leaderTokenHash: string;
  name: string;
  createdAt: number;
  destination: Destination | null;
}

export interface SocketAttachment {
  clientId: string | null;
  joined: boolean;
  isLeader: boolean;
  displayName?: string;
  carName?: string;
  avatarColor?: string;
  lastLocationAt?: number;
  msgWindowStart: number;
  msgCount: number;
}

export type ClientMessageType =
  | "join"
  | "leave"
  | "location_update"
  | "destination_update"
  | "map_mark"
  | "map_mark_clear"
  | "route_update"
  | "route_clear"
  | "end_trip"
  | "ptt_request"
  | "ptt_release"
  | "webrtc_offer"
  | "webrtc_answer"
  | "ice_candidate"
  | "chat_message"
  | "audio_chunk"
  | "ping";

export type ServerMessageType =
  | "joined"
  | "member_joined"
  | "member_left"
  | "members_snapshot"
  | "location_update"
  | "destination_update"
  | "map_mark"
  | "map_mark_clear"
  | "route_update"
  | "route_clear"
  | "marks_snapshot"
  | "trip_ended"
  | "leader_change"
  | "ptt_granted"
  | "ptt_busy"
  | "ptt_release"
  | "webrtc_offer"
  | "webrtc_answer"
  | "ice_candidate"
  | "chat_message"
  | "audio_chunk"
  | "pong"
  | "error";

export interface BaseMessage {
  type: string;
  version: number;
  timestamp: number;
}

export interface JoinMessage extends BaseMessage {
  type: "join";
  clientId: string;
  displayName: string;
  carName?: string;
  avatarColor?: string;
  inviteCode: string;
  leaderToken?: string;
}

export interface LocationUpdateMessage extends BaseMessage {
  type: "location_update";
  latitude: number;
  longitude: number;
  accuracy?: number;
  speed?: number;
  heading?: number;
}

export interface DestinationUpdateMessage extends BaseMessage {
  type: "destination_update";
  latitude: number;
  longitude: number;
  label?: string;
  leaderToken: string;
}

export interface MapMarkMessage extends BaseMessage {
  type: "map_mark";
  latitude: number;
  longitude: number;
  color?: string;
}

export interface MapMarkClearMessage extends BaseMessage {
  type: "map_mark_clear";
}

export interface RouteUpdateMessage extends BaseMessage {
  type: "route_update";
  colorHex?: string;
  points: Array<[number, number]>;
  segments?: Array<{ colorHex: string; points: Array<[number, number]> }>;
}

export interface RouteClearMessage extends BaseMessage {
  type: "route_clear";
}

export interface EndTripMessage extends BaseMessage {
  type: "end_trip";
  leaderToken: string;
}

export interface PttMessage extends BaseMessage {
  type: "ptt_request" | "ptt_release";
}

export interface WebrtcSignalMessage extends BaseMessage {
  type: "webrtc_offer" | "webrtc_answer";
  targetId: string;
  sdp: string;
}

export interface IceCandidateMessage extends BaseMessage {
  type: "ice_candidate";
  targetId: string;
  candidate: {
    candidate: string;
    sdpMid?: string | null;
    sdpMLineIndex?: number | null;
  };
}

export interface PingMessage extends BaseMessage {
  type: "ping";
}

export interface LeaveMessage extends BaseMessage {
  type: "leave";
}

export interface ChatMessage extends BaseMessage {
  type: "chat_message";
  text: string;
  clientId?: string;
  senderName?: string;
  senderColor?: string;
}

export interface AudioChunkMessage extends BaseMessage {
  type: "audio_chunk";
  data: string;
  sampleRate?: number;
  seq?: number;
  clientId?: string;
}

export type ClientMessage =
  | JoinMessage
  | LeaveMessage
  | LocationUpdateMessage
  | DestinationUpdateMessage
  | MapMarkMessage
  | MapMarkClearMessage
  | RouteUpdateMessage
  | RouteClearMessage
  | EndTripMessage
  | PttMessage
  | WebrtcSignalMessage
  | IceCandidateMessage
  | ChatMessage
  | AudioChunkMessage
  | PingMessage;

export interface ErrorMessage extends BaseMessage {
  type: "error";
  code: string;
  message: string;
}

export type ErrorCode =
  | "INVALID_MESSAGE"
  | "UNAUTHORIZED"
  | "NOT_JOINED"
  | "RATE_LIMITED"
  | "INVALID_LOCATION"
  | "TRIP_NOT_FOUND"
  | "DUPLICATE_CLIENT"
  | "PTT_BUSY"
  | "PAYLOAD_TOO_LARGE"
  | "FORBIDDEN";

export interface CreateTripRequest {
  name?: string;
  displayName?: string;
  carName?: string;
  avatarColor?: string;
}

export interface CreateTripResponse {
  tripId: string;
  inviteCode: string;
  leaderToken: string;
  leaderId: string;
  name: string;
  joinUrl: string;
  qrPayload: string;
}
