import { DurableObject } from "cloudflare:workers";
import type { Env } from "./env";
import { sendFcmToTokens } from "./fcm";
import {
  MAX_DISPLAY_NAME,
  PROTOCOL_VERSION,
  type Destination,
  type MapMark,
  type MemberPublic,
  type SocketAttachment,
  type TripPersistedState,
} from "./types";
import {
  generateInviteCode,
  generateTripId,
  normalizeInviteCode,
  nowMs,
  randomToken,
  sanitizeName,
  sha256Hex,
  timingSafeEqual,
} from "./utils";
import { parseClientMessage, ProtocolError } from "./validation";

const RATE_LIMIT_WINDOW_MS = 10_000;
const RATE_LIMIT_MAX = 200;
const LOCATION_MIN_INTERVAL_MS = 400;
/** Drop marks that outlive a drive session so reconnect doesn't revive ancient pins. */
const MARK_TTL_MS = 30 * 60 * 1000;

interface MemberRuntime {
  displayName: string;
  carName?: string;
  memberKind?: "vehicle" | "person";
  avatarColor?: string;
  latitude?: number;
  longitude?: number;
  accuracy?: number;
  speed?: number;
  heading?: number;
  lastLocationAt?: number;
  lastSeenAt: number;
  isLeader: boolean;
}

export class TripRoom extends DurableObject<Env> {
  private members = new Map<string, MemberRuntime>();
  private socketsByClient = new Map<string, WebSocket>();
  /** clientId → FCM device token for offline presence pushes */
  private fcmTokens = new Map<string, string>();
  private marks = new Map<string, MapMark>();
  private routes = new Map<
    string,
    {
      clientId: string;
      colorHex: string;
      points: Array<[number, number]>;
      segments?: Array<{ colorHex: string; points: Array<[number, number]> }>;
    }
  >();
  private activeSpeakers = new Set<string>();
  private trip: TripPersistedState | null = null;
  private initialized = false;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.ctx.blockConcurrencyWhile(async () => {
      await this.ensureLoaded();
      for (const ws of this.ctx.getWebSockets()) {
        const att = (ws.deserializeAttachment() ?? {}) as SocketAttachment;
        if (att.clientId && att.joined) {
          this.socketsByClient.set(att.clientId, ws);
          if (!this.members.has(att.clientId)) {
            this.members.set(att.clientId, {
              displayName: att.displayName ?? "Member",
              carName: att.carName,
              memberKind: att.memberKind ?? "vehicle",
              avatarColor: att.avatarColor,
              lastSeenAt: nowMs(),
              isLeader: att.isLeader,
            });
          }
        }
      }
    });
  }

  private async ensureLoaded(): Promise<void> {
    if (this.initialized) return;
    this.trip = (await this.ctx.storage.get<TripPersistedState>("trip")) ?? null;
    const members =
      (await this.ctx.storage.get<Record<string, MemberRuntime>>("membersLite")) ??
      {};
    for (const [id, m] of Object.entries(members)) {
      this.members.set(id, m);
    }
    const tokens =
      (await this.ctx.storage.get<Record<string, string>>("fcmTokens")) ?? {};
    for (const [id, token] of Object.entries(tokens)) {
      if (token) this.fcmTokens.set(id, token);
    }
    this.initialized = true;
  }

  private async persistTrip(): Promise<void> {
    if (this.trip) {
      await this.ctx.storage.put("trip", this.trip);
    }
  }

  private async persistMembersLite(): Promise<void> {
    const lite: Record<string, MemberRuntime> = {};
    for (const [id, m] of this.members) {
      lite[id] = {
        displayName: m.displayName,
        carName: m.carName,
        memberKind: m.memberKind ?? "vehicle",
        avatarColor: m.avatarColor,
        lastSeenAt: m.lastSeenAt,
        isLeader: m.isLeader,
        // intentionally omit high-freq lat/lng from durable writes
      };
    }
    await this.ctx.storage.put("membersLite", lite);
  }

  private async persistFcmTokens(): Promise<void> {
    const out: Record<string, string> = {};
    for (const [id, token] of this.fcmTokens) {
      out[id] = token;
    }
    await this.ctx.storage.put("fcmTokens", out);
  }

  async fetch(request: Request): Promise<Response> {
    await this.ensureLoaded();
    const url = new URL(request.url);

    if (request.method === "POST" && url.pathname.endsWith("/create")) {
      return this.handleCreate(request);
    }

    if (request.method === "GET" && url.searchParams.get("meta") === "1") {
      if (!this.trip) {
        return Response.json({ error: { code: "TRIP_NOT_FOUND", message: "Not found" } }, { status: 404 });
      }
      return Response.json({
        tripId: this.trip.tripId,
        name: this.trip.name,
        memberCount: this.socketsByClient.size,
        hasDestination: !!this.trip.destination,
      });
    }

    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Caravan TripRoom — upgrade required", { status: 426 });
    }

    if (!this.trip) {
      return Response.json(
        { error: { code: "TRIP_NOT_FOUND", message: "Trip does not exist" } },
        { status: 404 },
      );
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair) as [WebSocket, WebSocket];

    const attachment: SocketAttachment = {
      clientId: null,
      joined: false,
      isLeader: false,
      msgWindowStart: nowMs(),
      msgCount: 0,
    };
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(attachment);

    return new Response(null, { status: 101, webSocket: client });
  }

  private async handleCreate(request: Request): Promise<Response> {
    await this.ensureLoaded();
    if (this.trip) {
      return Response.json(
        { error: { code: "FORBIDDEN", message: "Trip already initialized" } },
        { status: 409 },
      );
    }

    let body: Record<string, unknown> = {};
    try {
      body = (await request.json()) as Record<string, unknown>;
    } catch {
      body = {};
    }

    const tripId = typeof body.tripId === "string" ? body.tripId : generateTripId();
    const name =
      sanitizeName(body.name, 60) ||
      sanitizeName(body.displayName, MAX_DISPLAY_NAME) ||
      "Caravan Trip";
    const inviteCode = generateInviteCode();
    const leaderToken = randomToken(24);
    const leaderTokenHash = await sha256Hex(leaderToken);
    const leaderId =
      typeof body.leaderId === "string" && body.leaderId
        ? body.leaderId.slice(0, 64)
        : crypto.randomUUID();

    this.trip = {
      tripId,
      inviteCode,
      inviteCodeNormalized: normalizeInviteCode(inviteCode),
      leaderId,
      leaderTokenHash,
      name,
      createdAt: nowMs(),
      destination: null,
    };
    await this.persistTrip();

    return Response.json({
      tripId,
      inviteCode,
      leaderToken,
      leaderId,
      name,
    });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    try {
      await this.ensureLoaded();
      const att = this.getAttachment(ws);

      // Peek type cheaply so live PCM isn't killed by the general chat/location rate limit.
      let peekedType: string | null = null;
      if (typeof message === "string") {
        try {
          const preview = JSON.parse(message) as { type?: unknown };
          if (typeof preview.type === "string") peekedType = preview.type;
        } catch {
          // parseClientMessage will surface the real error
        }
      }

      if (peekedType !== "audio_chunk" && !this.checkRateLimit(ws, att)) {
        this.sendError(ws, "RATE_LIMITED", "Too many messages");
        return;
      }

      const msg = parseClientMessage(message);

      switch (msg.type) {
        case "join":
          await this.handleJoin(ws, msg);
          break;
        case "leave":
          await this.handleLeave(ws);
          break;
        case "location_update":
          this.handleLocation(ws, msg);
          break;
        case "destination_update":
          await this.handleDestination(ws, msg);
          break;
        case "destination_clear":
          await this.handleDestinationClear(ws, msg);
          break;
        case "map_mark":
          this.handleMapMark(ws, msg);
          break;
        case "map_mark_clear":
          this.handleMapMarkClear(ws);
          break;
        case "route_update":
          this.handleRouteUpdate(ws, msg);
          break;
        case "route_clear":
          this.handleRouteClear(ws);
          break;
        case "end_trip":
          await this.handleEndTrip(ws, msg);
          break;
        case "ptt_request":
          this.handlePttRequest(ws);
          break;
        case "ptt_release":
          this.handlePttRelease(ws);
          break;
        case "webrtc_offer":
        case "webrtc_answer":
        case "ice_candidate":
          this.forwardSignal(ws, msg);
          break;
        case "chat_message":
          this.handleChatMessage(ws, msg);
          break;
        case "audio_chunk":
          this.handleAudioChunk(ws, msg);
          break;
        case "register_push":
          await this.handleRegisterPush(ws, msg);
          break;
        case "ping":
          this.send(ws, { type: "pong", version: PROTOCOL_VERSION, timestamp: nowMs() });
          break;
      }
    } catch (err) {
      if (err instanceof ProtocolError) {
        this.sendError(ws, err.code, err.message);
        return;
      }
      this.sendError(ws, "INVALID_MESSAGE", "Unexpected error processing message");
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    await this.detachSocket(ws, true);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    await this.detachSocket(ws, true);
  }

  private getAttachment(ws: WebSocket): SocketAttachment {
    const att = (ws.deserializeAttachment() ?? {
      clientId: null,
      joined: false,
      isLeader: false,
      msgWindowStart: nowMs(),
      msgCount: 0,
    }) as SocketAttachment;
    return att;
  }

  private saveAttachment(ws: WebSocket, att: SocketAttachment): void {
    ws.serializeAttachment(att);
  }

  private checkRateLimit(ws: WebSocket, att: SocketAttachment): boolean {
    const t = nowMs();
    if (t - att.msgWindowStart > RATE_LIMIT_WINDOW_MS) {
      att.msgWindowStart = t;
      att.msgCount = 0;
    }
    att.msgCount += 1;
    this.saveAttachment(ws, att);
    return att.msgCount <= RATE_LIMIT_MAX;
  }

  private send(ws: WebSocket, payload: Record<string, unknown>): void {
    try {
      ws.send(JSON.stringify(payload));
    } catch {
      // socket may be closing
    }
  }

  private sendError(ws: WebSocket, code: string, message: string): void {
    this.send(ws, {
      type: "error",
      version: PROTOCOL_VERSION,
      timestamp: nowMs(),
      code,
      message,
    });
  }

  private broadcast(
    payload: Record<string, unknown>,
    except?: WebSocket,
  ): void {
    const data = JSON.stringify(payload);
    for (const socket of this.ctx.getWebSockets()) {
      if (except && socket === except) continue;
      const att = this.getAttachment(socket);
      if (!att.joined) continue;
      try {
        socket.send(data);
      } catch {
        // ignore
      }
    }
  }

  private memberPublic(id: string, m: MemberRuntime): MemberPublic {
    return {
      id,
      displayName: m.displayName,
      carName: m.carName,
      memberKind: m.memberKind ?? "vehicle",
      avatarColor: m.avatarColor,
      latitude: m.latitude,
      longitude: m.longitude,
      accuracy: m.accuracy,
      speed: m.speed,
      heading: m.heading,
      lastLocationAt: m.lastLocationAt,
      lastSeenAt: m.lastSeenAt,
      connectionStatus: this.socketsByClient.has(id) ? "connected" : "offline",
      isLeader: m.isLeader || this.trip?.leaderId === id,
    };
  }

  private snapshotMembers(): MemberPublic[] {
    return [...this.members.entries()].map(([id, m]) => this.memberPublic(id, m));
  }

  private async handleJoin(
    ws: WebSocket,
    msg: Extract<ReturnType<typeof parseClientMessage>, { type: "join" }>,
  ): Promise<void> {
    if (!this.trip) {
      this.sendError(ws, "TRIP_NOT_FOUND", "Trip does not exist");
      return;
    }

    const codeOk =
      normalizeInviteCode(msg.inviteCode) === this.trip.inviteCodeNormalized;
    if (!codeOk) {
      this.sendError(ws, "UNAUTHORIZED", "Invalid invite code");
      return;
    }

    const existing = this.socketsByClient.get(msg.clientId);
    if (existing && existing !== ws) {
      try {
        existing.close(4000, "Replaced by new connection");
      } catch {
        // ignore
      }
      this.socketsByClient.delete(msg.clientId);
    }

    let isLeader = false;
    if (msg.leaderToken) {
      const hash = await sha256Hex(msg.leaderToken);
      if (timingSafeEqual(hash, this.trip.leaderTokenHash)) {
        isLeader = true;
        if (this.trip.leaderId !== msg.clientId) {
          this.trip.leaderId = msg.clientId;
          await this.persistTrip();
          this.broadcast({
            type: "leader_change",
            version: PROTOCOL_VERSION,
            timestamp: nowMs(),
            leaderId: msg.clientId,
          });
        }
      }
    }

    if (msg.clientId === this.trip.leaderId) {
      // Leader id claim without token is not enough; only token grants leader actions.
      // Presence still marks known leader id for UI if token matched above.
    }

    const runtime: MemberRuntime = {
      displayName: msg.displayName,
      carName: msg.carName,
      memberKind: msg.memberKind ?? "vehicle",
      avatarColor: this.pickDistinctColor(msg.avatarColor, msg.clientId),
      lastSeenAt: nowMs(),
      isLeader,
      latitude: this.members.get(msg.clientId)?.latitude,
      longitude: this.members.get(msg.clientId)?.longitude,
      accuracy: this.members.get(msg.clientId)?.accuracy,
      speed: this.members.get(msg.clientId)?.speed,
      heading: this.members.get(msg.clientId)?.heading,
      lastLocationAt: this.members.get(msg.clientId)?.lastLocationAt,
    };
    // Preserve leader flag for the canonical leader id when token used
    if (isLeader || msg.clientId === this.trip.leaderId && isLeader) {
      runtime.isLeader = true;
    } else {
      runtime.isLeader = false;
    }
    // Fix: only token-authenticated leader is isLeader for actions; UI uses trip.leaderId
    runtime.isLeader = isLeader || msg.clientId === this.trip.leaderId;

    this.members.set(msg.clientId, runtime);
    this.socketsByClient.set(msg.clientId, ws);

    const att: SocketAttachment = {
      clientId: msg.clientId,
      joined: true,
      isLeader,
      displayName: msg.displayName,
      carName: msg.carName,
      memberKind: runtime.memberKind,
      avatarColor: runtime.avatarColor,
      msgWindowStart: nowMs(),
      msgCount: 0,
    };
    this.saveAttachment(ws, att);
    await this.persistMembersLite();

    this.pruneStaleMarks();

    this.send(ws, {
      type: "joined",
      version: PROTOCOL_VERSION,
      timestamp: nowMs(),
      tripId: this.trip.tripId,
      tripName: this.trip.name,
      clientId: msg.clientId,
      leaderId: this.trip.leaderId,
      members: this.snapshotMembers(),
      destination: this.trip.destination,
      marks: Array.from(this.marks.values()),
      routes: Array.from(this.routes.values()),
      activeSpeakerId: this.activeSpeakers.values().next().value ?? null,
      inviteCode: this.trip.inviteCode,
    });

    this.broadcast(
      {
        type: "member_joined",
        version: PROTOCOL_VERSION,
        timestamp: nowMs(),
        member: this.memberPublic(msg.clientId, runtime),
      },
      ws,
    );

    this.broadcast({
      type: "members_snapshot",
      version: PROTOCOL_VERSION,
      timestamp: nowMs(),
      members: this.snapshotMembers(),
    });

    // Wake offline members (have FCM token, no live socket) that someone came online.
    this.ctx.waitUntil(
      this.pushPresenceToOfflineMembers(msg.clientId, runtime.displayName),
    );
  }

  private async handleRegisterPush(
    ws: WebSocket,
    msg: Extract<ReturnType<typeof parseClientMessage>, { type: "register_push" }>,
  ): Promise<void> {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId) {
      this.sendError(ws, "NOT_JOINED", "Join before registering push");
      return;
    }
    const token = msg.fcmToken.trim();
    if (!token) {
      this.sendError(ws, "INVALID_MESSAGE", "fcmToken required");
      return;
    }
    // One device token per client; also drop any other client sharing the same token.
    for (const [id, existing] of this.fcmTokens) {
      if (existing === token && id !== att.clientId) {
        this.fcmTokens.delete(id);
      }
    }
    this.fcmTokens.set(att.clientId, token);
    await this.persistFcmTokens();
  }

  private async pushPresenceToOfflineMembers(
    joinerId: string,
    displayName: string,
  ): Promise<void> {
    if (!this.env.FCM_SERVICE_ACCOUNT_JSON) return;
    if (!this.trip) return;

    const tokens: string[] = [];
    const tokenOwners: string[] = [];
    for (const [clientId, token] of this.fcmTokens) {
      if (clientId === joinerId) continue;
      // Live WebSocket clients already get member_joined — skip them.
      if (this.socketsByClient.has(clientId)) continue;
      tokens.push(token);
      tokenOwners.push(clientId);
    }
    if (tokens.length === 0) return;

    const tripName = (this.trip.name || "").trim() || "Convoy";
    const body = `${displayName} joined convoy “${tripName}”`;

    const dead = await sendFcmToTokens(this.env.FCM_SERVICE_ACCOUNT_JSON, tokens, {
      title: tripName,
      body,
      data: {
        type: "member_online",
        tripId: this.trip.tripId,
        tripName,
        clientId: joinerId,
        displayName,
        body,
      },
    });
    if (dead.length === 0) return;
    let changed = false;
    for (let i = 0; i < tokens.length; i++) {
      if (dead.includes(tokens[i]!)) {
        this.fcmTokens.delete(tokenOwners[i]!);
        changed = true;
      }
    }
    if (changed) await this.persistFcmTokens();
  }

  private pruneStaleMarks(): void {
    const now = nowMs();
    for (const [id, mark] of this.marks) {
      if (now - (mark.updatedAt ?? 0) > MARK_TTL_MS) {
        this.marks.delete(id);
      }
    }
  }

  private async handleLeave(ws: WebSocket): Promise<void> {
    await this.detachSocket(ws, true);
    try {
      ws.close(1000, "left");
    } catch {
      // ignore
    }
  }

  private async detachSocket(ws: WebSocket, announce: boolean): Promise<void> {
    const att = this.getAttachment(ws);
    const clientId = att.clientId;
    if (clientId) {
      // Replaced socket: new join already owns clientId — do not wipe its route/membership.
      if (this.socketsByClient.get(clientId) !== ws) {
        this.saveAttachment(ws, {
          ...att,
          joined: false,
          clientId: null,
        });
        return;
      }
      this.socketsByClient.delete(clientId);
      const m = this.members.get(clientId);
      if (m) {
        m.lastSeenAt = nowMs();
        this.members.set(clientId, m);
      }
      if (this.activeSpeakers.delete(clientId)) {
        this.broadcast({
          type: "ptt_release",
          version: PROTOCOL_VERSION,
          timestamp: nowMs(),
          clientId,
        });
      }
      if (this.marks.delete(clientId)) {
        this.broadcast({
          type: "map_mark_clear",
          version: PROTOCOL_VERSION,
          timestamp: nowMs(),
          clientId,
        });
      }
      if (this.routes.delete(clientId)) {
        this.broadcast({
          type: "route_clear",
          version: PROTOCOL_VERSION,
          timestamp: nowMs(),
          clientId,
        });
      }
      if (announce && att.joined) {
        this.broadcast({
          type: "member_left",
          version: PROTOCOL_VERSION,
          timestamp: nowMs(),
          clientId,
        });
        this.broadcast({
          type: "members_snapshot",
          version: PROTOCOL_VERSION,
          timestamp: nowMs(),
          members: this.snapshotMembers(),
        });
      }
      await this.persistMembersLite();
    }
    this.saveAttachment(ws, {
      ...att,
      joined: false,
      clientId: null,
    });
  }

  private handleLocation(
    ws: WebSocket,
    msg: Extract<ReturnType<typeof parseClientMessage>, { type: "location_update" }>,
  ): void {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId) {
      this.sendError(ws, "NOT_JOINED", "Join required");
      return;
    }
    const m = this.members.get(att.clientId);
    if (!m) {
      this.sendError(ws, "NOT_JOINED", "Unknown member");
      return;
    }
    if (m.lastLocationAt && msg.timestamp < m.lastLocationAt) {
      return; // stale
    }
    if (m.lastLocationAt && msg.timestamp - m.lastLocationAt < LOCATION_MIN_INTERVAL_MS) {
      // soft throttle — still accept but skip broadcast if too frequent
      // allow update of local state lightly
    }

    m.latitude = msg.latitude;
    m.longitude = msg.longitude;
    m.accuracy = msg.accuracy;
    m.speed = msg.speed;
    m.heading = msg.heading;
    m.lastLocationAt = msg.timestamp;
    m.lastSeenAt = nowMs();
    this.members.set(att.clientId, m);
    att.lastLocationAt = msg.timestamp;
    this.saveAttachment(ws, att);

    this.broadcast(
      {
        type: "location_update",
        version: PROTOCOL_VERSION,
        timestamp: msg.timestamp,
        clientId: att.clientId,
        latitude: msg.latitude,
        longitude: msg.longitude,
        accuracy: msg.accuracy,
        speed: msg.speed,
        heading: msg.heading,
      },
      ws,
    );
  }

  private handleMapMark(
    ws: WebSocket,
    msg: Extract<ReturnType<typeof parseClientMessage>, { type: "map_mark" }>,
  ): void {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId) {
      this.sendError(ws, "NOT_JOINED", "Join required");
      return;
    }
    const runtime = this.members.get(att.clientId);
    const color =
      (typeof msg.color === "string" && msg.color) ||
      runtime?.avatarColor ||
      "#0EA5E9";
    const mark: MapMark = {
      clientId: att.clientId,
      displayName: att.displayName ?? runtime?.displayName ?? "Driver",
      latitude: msg.latitude,
      longitude: msg.longitude,
      color,
      updatedAt: nowMs(),
    };
    this.marks.set(att.clientId, mark);
    this.broadcast(
      {
        type: "map_mark",
        version: PROTOCOL_VERSION,
        timestamp: nowMs(),
        mark,
      },
      ws,
    );
  }

  private handleMapMarkClear(ws: WebSocket): void {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId) {
      this.sendError(ws, "NOT_JOINED", "Join required");
      return;
    }
    if (!this.marks.has(att.clientId)) return;
    this.marks.delete(att.clientId);
    this.broadcast(
      {
        type: "map_mark_clear",
        version: PROTOCOL_VERSION,
        timestamp: nowMs(),
        clientId: att.clientId,
      },
      ws,
    );
  }

  private handleRouteUpdate(
    ws: WebSocket,
    msg: Extract<ReturnType<typeof parseClientMessage>, { type: "route_update" }>,
  ): void {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId) {
      this.sendError(ws, "NOT_JOINED", "Join required");
      return;
    }
    const runtime = this.members.get(att.clientId);
    const colorHex =
      (typeof msg.colorHex === "string" && msg.colorHex) ||
      runtime?.avatarColor ||
      "#2563EB";
    const route = {
      clientId: att.clientId,
      colorHex,
      points: msg.points,
      segments: msg.segments,
    };
    this.routes.set(att.clientId, route);
    this.broadcast(
      {
        type: "route_update",
        version: PROTOCOL_VERSION,
        timestamp: nowMs(),
        ...route,
      },
      ws,
    );
  }

  private handleRouteClear(ws: WebSocket): void {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId) {
      this.sendError(ws, "NOT_JOINED", "Join required");
      return;
    }
    this.routes.delete(att.clientId);
    this.broadcast(
      {
        type: "route_clear",
        version: PROTOCOL_VERSION,
        timestamp: nowMs(),
        clientId: att.clientId,
      },
      ws,
    );
  }

  private async handleEndTrip(
    ws: WebSocket,
    msg: Extract<ReturnType<typeof parseClientMessage>, { type: "end_trip" }>,
  ): Promise<void> {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId || !this.trip) {
      this.sendError(ws, "NOT_JOINED", "Join required");
      return;
    }
    const hash = await sha256Hex(msg.leaderToken);
    if (!timingSafeEqual(hash, this.trip.leaderTokenHash)) {
      this.sendError(ws, "UNAUTHORIZED", "Leader token required");
      return;
    }

    const inviteCode = this.trip.inviteCode;
    this.broadcast({
      type: "trip_ended",
      version: PROTOCOL_VERSION,
      timestamp: nowMs(),
      tripId: this.trip.tripId,
    });

    // Remove invite index so the code cannot be reused/joined.
    try {
      const invites = this.env.INVITES.get(this.env.INVITES.idFromName("global"));
      await invites.fetch(
        new Request(
          `https://invite.internal/delete?code=${encodeURIComponent(inviteCode)}`,
          { method: "DELETE" },
        ),
      );
    } catch {
      // Best-effort cleanup
    }

    this.marks.clear();
    this.members.clear();
    this.activeSpeakers.clear();
    this.trip = null;
    await this.ctx.storage.deleteAll();

    for (const sock of [...this.ctx.getWebSockets()]) {
      try {
        sock.close(1000, "trip_ended");
      } catch {
        // ignore
      }
    }
    this.socketsByClient.clear();
  }

  private async handleDestination(
    ws: WebSocket,
    msg: Extract<ReturnType<typeof parseClientMessage>, { type: "destination_update" }>,
  ): Promise<void> {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId || !this.trip) {
      this.sendError(ws, "NOT_JOINED", "Join required");
      return;
    }
    const hash = await sha256Hex(msg.leaderToken);
    if (!timingSafeEqual(hash, this.trip.leaderTokenHash)) {
      this.sendError(ws, "UNAUTHORIZED", "Leader token required");
      return;
    }
    if (
      typeof msg.ifUpdatedAt === "number" &&
      this.trip.destination &&
      this.trip.destination.updatedAt !== msg.ifUpdatedAt
    ) {
      this.sendError(ws, "INVALID_MESSAGE", "Stale destination");
      this.send(ws, {
        type: "destination_update",
        version: PROTOCOL_VERSION,
        timestamp: nowMs(),
        destination: this.trip.destination,
      });
      return;
    }

    const dest: Destination = {
      latitude: msg.latitude,
      longitude: msg.longitude,
      label: msg.label,
      updatedAt: nowMs(),
      updatedById: att.clientId,
      updatedByName: att.displayName ?? "Leader",
    };
    this.trip.destination = dest;
    this.trip.leaderId = att.clientId;
    await this.persistTrip();

    this.broadcast({
      type: "destination_update",
      version: PROTOCOL_VERSION,
      timestamp: nowMs(),
      destination: dest,
    });
  }

  private async handleDestinationClear(
    ws: WebSocket,
    msg: Extract<ReturnType<typeof parseClientMessage>, { type: "destination_clear" }>,
  ): Promise<void> {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId || !this.trip) {
      this.sendError(ws, "NOT_JOINED", "Join required");
      return;
    }
    const hash = await sha256Hex(msg.leaderToken);
    if (!timingSafeEqual(hash, this.trip.leaderTokenHash)) {
      this.sendError(ws, "UNAUTHORIZED", "Leader token required");
      return;
    }
    if (
      typeof msg.ifUpdatedAt === "number" &&
      this.trip.destination &&
      this.trip.destination.updatedAt !== msg.ifUpdatedAt
    ) {
      this.sendError(ws, "INVALID_MESSAGE", "Stale destination");
      this.send(ws, {
        type: "destination_update",
        version: PROTOCOL_VERSION,
        timestamp: nowMs(),
        destination: this.trip.destination,
      });
      return;
    }

    this.trip.destination = null;
    await this.persistTrip();
    this.broadcast({
      type: "destination_clear",
      version: PROTOCOL_VERSION,
      timestamp: nowMs(),
      clientId: att.clientId,
    });
  }

  private handlePttRequest(ws: WebSocket): void {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId) {
      this.sendError(ws, "NOT_JOINED", "Join required");
      return;
    }
    // Open mic: multiple members can talk at once (no exclusive floor).
    this.activeSpeakers.add(att.clientId);
    this.broadcast({
      type: "ptt_granted",
      version: PROTOCOL_VERSION,
      timestamp: nowMs(),
      clientId: att.clientId,
      displayName: att.displayName,
    });
  }

  private handlePttRelease(ws: WebSocket): void {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId) return;
    if (!this.activeSpeakers.delete(att.clientId)) return;
    this.broadcast({
      type: "ptt_release",
      version: PROTOCOL_VERSION,
      timestamp: nowMs(),
      clientId: att.clientId,
    });
  }

  private handleChatMessage(
    ws: WebSocket,
    msg: Extract<ReturnType<typeof parseClientMessage>, { type: "chat_message" }>,
  ): void {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId) {
      this.sendError(ws, "NOT_JOINED", "Join required");
      return;
    }
    this.broadcast({
      type: "chat_message",
      version: PROTOCOL_VERSION,
      timestamp: nowMs(),
      clientId: att.clientId,
      senderName: att.displayName ?? "Driver",
      senderColor: att.avatarColor ?? "#1976D2",
      text: msg.text,
    });
  }

  private handleAudioChunk(
    ws: WebSocket,
    msg: Extract<ReturnType<typeof parseClientMessage>, { type: "audio_chunk" }>,
  ): void {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId) return;
    // Relay immediately — no floor/queue gate. PTT signals are UI-only.
    this.activeSpeakers.add(att.clientId);
    this.broadcast(
      {
        type: "audio_chunk",
        version: PROTOCOL_VERSION,
        timestamp: nowMs(),
        clientId: att.clientId,
        data: msg.data,
        sampleRate: msg.sampleRate,
        seq: msg.seq,
      },
      ws,
    );
  }

  private forwardSignal(
    ws: WebSocket,
    msg: Extract<
      ReturnType<typeof parseClientMessage>,
      { type: "webrtc_offer" | "webrtc_answer" | "ice_candidate" }
    >,
  ): void {
    const att = this.getAttachment(ws);
    if (!att.joined || !att.clientId) {
      this.sendError(ws, "NOT_JOINED", "Join required");
      return;
    }
    const target = this.socketsByClient.get(msg.targetId);
    if (!target) return;
    if (msg.type === "ice_candidate") {
      this.send(target, {
        type: "ice_candidate",
        version: PROTOCOL_VERSION,
        timestamp: nowMs(),
        fromId: att.clientId,
        targetId: msg.targetId,
        candidate: msg.candidate,
      });
    } else {
      this.send(target, {
        type: msg.type,
        version: PROTOCOL_VERSION,
        timestamp: nowMs(),
        fromId: att.clientId,
        targetId: msg.targetId,
        sdp: msg.sdp,
      });
    }
  }

  private pickDistinctColor(
    preferred: string | undefined,
    clientId: string,
  ): string {
    const palette = [
      "#0EA5E9",
      "#F59E0B",
      "#10B981",
      "#EF4444",
      "#8B5CF6",
      "#EC4899",
      "#14B8A6",
      "#F97316",
      "#6366F1",
      "#84CC16",
      "#06B6D4",
      "#D946EF",
    ];
    const normalize = (c?: string) =>
      (c ?? "").trim().toUpperCase().replace(/^#?/, "#");
    const taken = new Set<string>();
    for (const [id, m] of this.members) {
      if (id === clientId) continue;
      const n = normalize(m.avatarColor);
      if (n.length > 1) taken.add(n);
    }
    const pref = normalize(preferred);
    if (pref.length > 1 && !taken.has(pref)) return pref;
    for (const c of palette) {
      if (!taken.has(c)) return c;
    }
    let hash = 0;
    for (let i = 0; i < clientId.length; i++) {
      hash = (hash * 31 + clientId.charCodeAt(i)) >>> 0;
    }
    return palette[hash % palette.length]!;
  }
}
