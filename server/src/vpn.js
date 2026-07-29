import { connect } from "cloudflare:sockets";

import { FixedWindowRateLimiter } from "./rate-limit.js";
import {
  buildSubscription,
  constantTimeString,
  isIncompleteVlessHeaderError,
  normalizeUuid,
  parseVlessHeader,
} from "./vless.js";

const WEBSOCKET_PATH = "/ws";
const MAX_VLESS_HEADER_BYTES = 8192;
const SECRET_CACHE_TTL_MS = 60_000;
const rateLimiter = new FixedWindowRateLimiter();
const secretCache = new Map();

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const upgrade = (request.headers.get("Upgrade") || "").toLowerCase();

    if (url.pathname === "/sub" && request.method === "GET") {
      if (!allowRequest(request, "subscription", 30)) return rateLimited();
      return handleSubscription(request, env);
    }

    if (url.pathname === WEBSOCKET_PATH && upgrade === "websocket") {
      const uuid = normalizeUuid(await requiredKv(env, "secret:UUID"));
      return createVlessSession(uuid);
    }

    return new Response("Not found", {
      status: 404,
      headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" },
    });
  },
};

async function handleSubscription(request, env) {
  const token = new URL(request.url).searchParams.get("token") || "";
  const expectedToken = await requiredKv(env, "secret:SUB_TOKEN");
  if (!constantTimeString(token, expectedToken)) {
    return new Response("Not found", { status: 404 });
  }

  const uuid = normalizeUuid(await requiredKv(env, "secret:UUID"));
  const hostname = new URL(request.url).hostname;
  return new Response(buildSubscription(hostname, uuid), {
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function createVlessSession(uuid) {
  const pair = new WebSocketPair();
  const client = pair[0];
  const server = pair[1];
  try {
    server.accept({ allowHalfOpen: true });
  } catch {
    server.accept();
  }
  server.binaryType = "arraybuffer";

  let socket;
  let writer;
  let writeChunk;
  let initialized = false;
  let chain = Promise.resolve();
  let headerBuffer = new Uint8Array(0);

  server.addEventListener("message", (event) => {
    chain = chain.then(async () => {
      const chunk = toBytes(event.data);
      if (!initialized) {
        headerBuffer = appendBytes(headerBuffer, chunk);
        if (headerBuffer.byteLength > MAX_VLESS_HEADER_BYTES) throw new Error("VLESS header is too large");
        let header;
        try {
          header = parseVlessHeader(headerBuffer, uuid);
        } catch (error) {
          if (isIncompleteVlessHeaderError(error)) return;
          throw error;
        }
        headerBuffer = new Uint8Array(0);
        if (header.command === 2) {
          if (header.port !== 53) throw new Error("Only UDP DNS on port 53 is supported");
          const dns = await createDnsSession(server, header.version);
          socket = dns.socket;
          writer = dns.writer;
          writeChunk = dns.write;
          initialized = true;
          if (header.payload.byteLength) await writeChunk(header.payload);
          return;
        }
        socket = connect({ hostname: header.hostname, port: header.port });
        await socket.opened;
        writer = socket.writable.getWriter();
        writeChunk = (data) => writer.write(data);
        if (header.payload.byteLength) await writer.write(header.payload);
        initialized = true;
        void pipeRemote(socket, server, header.version);
        return;
      }
      await writeChunk(chunk);
    }).catch(() => {
      closeSession(server, socket, writer);
    });
  });

  server.addEventListener("close", () => {
    closeSession(server, socket, writer);
  });
  server.addEventListener("error", () => {
    closeSession(server, socket, writer);
  });
  return new Response(null, { status: 101, webSocket: client });
}

async function createDnsSession(webSocket, version) {
  const socket = connect({ hostname: "8.8.8.8", port: 53 });
  await socket.opened;
  const writer = socket.writable.getWriter();
  let responseHeader = true;
  let incoming = new Uint8Array(0);
  void socket.readable.pipeTo(new WritableStream({
    async write(value) {
      incoming = appendBytes(incoming, toBytes(value));
      while (incoming.byteLength >= 2) {
        const length = (incoming[0] << 8) | incoming[1];
        if (incoming.byteLength < length + 2) return;
        const packet = incoming.subarray(2, length + 2);
        incoming = incoming.subarray(length + 2);
        const framed = new Uint8Array(packet.byteLength + 2 + (responseHeader ? 2 : 0));
        let offset = 0;
        if (responseHeader) {
          framed.set([version, 0]);
          offset = 2;
          responseHeader = false;
        }
        framed[offset] = packet.byteLength >> 8;
        framed[offset + 1] = packet.byteLength & 0xff;
        framed.set(packet, offset + 2);
        await sendWebSocket(webSocket, framed.buffer);
      }
    },
  })).catch(() => closeSession(webSocket, socket, writer));

  return {
    socket,
    writer,
    async write(data) {
      let packets = toBytes(data);
      while (packets.byteLength >= 2) {
        const length = (packets[0] << 8) | packets[1];
        if (packets.byteLength < length + 2) throw new Error("Incomplete UDP DNS packet");
        await writer.write(packets.subarray(0, length + 2));
        packets = packets.subarray(length + 2);
      }
      if (packets.byteLength) throw new Error("Incomplete UDP DNS packet");
    },
  };
}

async function pipeRemote(socket, webSocket, version) {
  let firstChunk = true;
  try {
    await socket.readable.pipeTo(new WritableStream({
      async write(value) {
        const chunk = toBytes(value);
        if (firstChunk) {
          const response = new Uint8Array(chunk.byteLength + 2);
          response.set([version, 0]);
          response.set(chunk, 2);
          await sendWebSocket(webSocket, response.buffer);
          firstChunk = false;
        } else {
          await sendWebSocket(webSocket, toArrayBuffer(chunk));
        }
      },
    }));
  } catch {
    // Closing either side terminates the session below.
  } finally {
    closeSession(webSocket, socket);
  }
}

function closeSession(webSocket, socket, writer) {
  try { writer?.releaseLock(); } catch {}
  try { socket?.close(); } catch {}
  try { webSocket.close(1000, "closed"); } catch {}
}

function toBytes(value) {
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  return new TextEncoder().encode(String(value));
}

function toArrayBuffer(value) {
  return value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength);
}

function appendBytes(left, right) {
  const merged = new Uint8Array(left.byteLength + right.byteLength);
  merged.set(left);
  merged.set(right, left.byteLength);
  return merged;
}

async function sendWebSocket(webSocket, payload) {
  if (webSocket.readyState !== WebSocket.OPEN) throw new Error("WebSocket is not open");
  const result = webSocket.send(payload);
  if (result && typeof result.then === "function") await result;
}

async function requiredKv(env, key) {
  const cached = secretCache.get(key);
  if (cached && cached.expiresAt > Date.now()) return cached.value;
  const value = await env.CONFIG.get(key);
  if (!value) throw new Error(`${key} is required`);
  secretCache.set(key, { value, expiresAt: Date.now() + SECRET_CACHE_TTL_MS });
  return value;
}

function allowRequest(request, scope, limit) {
  const address = request.headers.get("CF-Connecting-IP") || "unknown";
  return rateLimiter.allow(`${scope}:${address}`, limit);
}

function rateLimited() {
  return new Response("Too many requests", {
    status: 429,
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "no-store",
      "retry-after": "60",
    },
  });
}
