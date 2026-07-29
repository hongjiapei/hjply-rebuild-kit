import assert from "node:assert/strict";
import test from "node:test";

import {
  buildSubscription,
  constantTimeString,
  isIncompleteVlessHeaderError,
  normalizeUuid,
  parseVlessHeader,
} from "../src/vless.js";

const UUID = "c5f2f082-3189-4b11-ae4d-e18d7c62bc1f";

test("normalizes UUIDv4", () => {
  assert.equal(normalizeUuid(UUID.toUpperCase()), UUID);
});

test("parses a TCP domain request", () => {
  const uuid = Uint8Array.from(UUID.replaceAll("-", "").match(/.{2}/g), (hex) => Number.parseInt(hex, 16));
  const domain = new TextEncoder().encode("example.com");
  const packet = new Uint8Array(1 + 16 + 1 + 1 + 2 + 1 + 1 + domain.length + 3);
  let offset = 0;
  packet[offset++] = 1;
  packet.set(uuid, offset); offset += 16;
  packet[offset++] = 0;
  packet[offset++] = 1;
  packet[offset++] = 1; packet[offset++] = 187;
  packet[offset++] = 2;
  packet[offset++] = domain.length;
  packet.set(domain, offset); offset += domain.length;
  packet.set([1, 2, 3], offset);

  const parsed = parseVlessHeader(packet, UUID);
  assert.equal(parsed.hostname, "example.com");
  assert.equal(parsed.port, 443);
  assert.deepEqual([...parsed.payload], [1, 2, 3]);
});

test("parses a UDP DNS request", () => {
  const uuid = Uint8Array.from(UUID.replaceAll("-", "").match(/.{2}/g), (hex) => Number.parseInt(hex, 16));
  const packet = new Uint8Array(1 + 16 + 1 + 1 + 2 + 1 + 4);
  let offset = 0;
  packet[offset++] = 1;
  packet.set(uuid, offset); offset += 16;
  packet[offset++] = 0;
  packet[offset++] = 2;
  packet[offset++] = 0; packet[offset++] = 53;
  packet[offset++] = 1;
  packet.set([8, 8, 8, 8], offset);

  const parsed = parseVlessHeader(packet, UUID);
  assert.equal(parsed.command, 2);
  assert.equal(parsed.hostname, "8.8.8.8");
  assert.equal(parsed.port, 53);
});

test("recognizes a fragmented VLESS header", () => {
  assert.throws(
    () => parseVlessHeader(new Uint8Array(10), UUID),
    (error) => isIncompleteVlessHeaderError(error),
  );
});

test("rejects a wrong UUID", () => {
  const packet = new Uint8Array(24);
  packet[17] = 0;
  packet[18] = 1;
  packet[20] = 80;
  packet[21] = 1;
  packet.set([1, 1], 22);
  assert.throws(() => parseVlessHeader(packet, UUID), /Invalid UUID/);
});

test("builds an authenticated VLESS subscription", () => {
  const encoded = buildSubscription("vpn.example.com", UUID);
  const node = atob(encoded);
  const url = new URL(node);
  assert.equal(url.protocol, "vless:");
  assert.equal(url.username, UUID);
  assert.equal(url.hostname, "vpn.example.com");
  assert.equal(url.searchParams.get("security"), "tls");
  assert.equal(url.searchParams.get("type"), "ws");
  assert.equal(url.searchParams.get("path"), "/ws");
});

test("compares subscription tokens", () => {
  assert.equal(constantTimeString("same-token", "same-token"), true);
  assert.equal(constantTimeString("same-token", "other-token"), false);
  assert.equal(constantTimeString("short", "longer"), false);
});
