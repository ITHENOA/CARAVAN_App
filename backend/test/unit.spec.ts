import { describe, expect, it } from "vitest";
import {
  generateInviteCode,
  isValidLatLng,
  normalizeInviteCode,
  sanitizeName,
  sha256Hex,
  timingSafeEqual,
} from "../src/utils";
import { parseClientMessage, ProtocolError } from "../src/validation";

describe("utils", () => {
  it("generates invite codes like XXX-XXX", () => {
    const code = generateInviteCode();
    expect(code).toMatch(/^[A-Z0-9]{3}-[A-Z0-9]{3}$/);
  });

  it("normalizes invite codes", () => {
    expect(normalizeInviteCode("7k4-m2p")).toBe("7K4M2P");
  });

  it("sanitizes names", () => {
    expect(sanitizeName("  Ali\nReza  ", 40)).toBe("Ali Reza");
    expect(sanitizeName("a".repeat(100), 40).length).toBe(40);
  });

  it("validates coordinates", () => {
    expect(isValidLatLng(35.6, 51.4)).toBe(true);
    expect(isValidLatLng(91, 0)).toBe(false);
    expect(isValidLatLng(Number.NaN, 0)).toBe(false);
  });

  it("hashes and compares tokens safely", async () => {
    const a = await sha256Hex("secret");
    const b = await sha256Hex("secret");
    const c = await sha256Hex("other");
    expect(timingSafeEqual(a, b)).toBe(true);
    expect(timingSafeEqual(a, c)).toBe(false);
  });
});

describe("validation", () => {
  it("parses join messages", () => {
    const msg = parseClientMessage(
      JSON.stringify({
        type: "join",
        version: 1,
        timestamp: Date.now(),
        clientId: "c1",
        displayName: "Ali",
        inviteCode: "7K4-M2P",
      }),
    );
    expect(msg.type).toBe("join");
  });

  it("rejects malformed JSON", () => {
    expect(() => parseClientMessage("{")).toThrow(ProtocolError);
  });

  it("rejects unknown types", () => {
    expect(() =>
      parseClientMessage(
        JSON.stringify({ type: "nope", version: 1, timestamp: 1 }),
      ),
    ).toThrow(ProtocolError);
  });

  it("rejects invalid location", () => {
    expect(() =>
      parseClientMessage(
        JSON.stringify({
          type: "location_update",
          version: 1,
          timestamp: 1,
          latitude: 999,
          longitude: 0,
        }),
      ),
    ).toThrow(ProtocolError);
  });

  it("parses register_push messages", () => {
    const msg = parseClientMessage(
      JSON.stringify({
        type: "register_push",
        version: 1,
        timestamp: Date.now(),
        fcmToken: "tok_abc",
      }),
    );
    expect(msg.type).toBe("register_push");
    if (msg.type === "register_push") expect(msg.fcmToken).toBe("tok_abc");
  });
});
