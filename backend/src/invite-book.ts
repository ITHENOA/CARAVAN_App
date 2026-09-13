import { DurableObject } from "cloudflare:workers";
import { normalizeInviteCode } from "./utils";

/** Maps invite codes → trip ids so clients can join with a short code alone. */
export class InviteBook extends DurableObject {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "PUT") {
      const body = (await request.json()) as {
        inviteCode?: string;
        tripId?: string;
      };
      if (!body.inviteCode || !body.tripId) {
        return Response.json({ error: "bad request" }, { status: 400 });
      }
      const key = normalizeInviteCode(body.inviteCode);
      await this.ctx.storage.put(key, body.tripId);
      return Response.json({ ok: true });
    }
    if (request.method === "GET") {
      const code = url.searchParams.get("code");
      if (!code) return Response.json({ error: "missing code" }, { status: 400 });
      const tripId = await this.ctx.storage.get<string>(normalizeInviteCode(code));
      if (!tripId) {
        return Response.json(
          { error: { code: "TRIP_NOT_FOUND", message: "Unknown invite code" } },
          { status: 404 },
        );
      }
      return Response.json({ tripId });
    }
    if (request.method === "DELETE") {
      const code = url.searchParams.get("code");
      if (!code) return Response.json({ error: "missing code" }, { status: 400 });
      await this.ctx.storage.delete(normalizeInviteCode(code));
      return Response.json({ ok: true });
    }
    return new Response("InviteBook", { status: 404 });
  }
}
