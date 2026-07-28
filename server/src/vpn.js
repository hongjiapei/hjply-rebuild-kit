import { connect } from "cloudflare:sockets";

import { normalizeUuid, parseVlessHeader } from "./vless.js";

const WEBSOCKET_PATH = "/ws";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const upgrade = (request.headers.get("Upgrade") || "").toLowerCase();

    if (url.pathname === "/sub" && request.method === "GET") {
      return handleSubscription(request, env);
    }

    if (url.pathname === WEBSOCKET_PATH && upgrade === "websocket") {
      const uuid = normalizeUuid(await requiredKv(env, "secret:UUID"));
      return createVlessSession(uuid, env);
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
  const query = new URLSearchParams({
    encryption: "none",
    security: "tls",
    sni: hostname,
    fp: "chrome",
    type: "ws",
    host: hostname,
    path: WEBSOCKET_PATH,
  });
  const node = `vless://${uuid}@${hostname}:443?${query.toString()}#HJPLY%20%E7%A8%B3%E5%AE%9A%E8%8A%82%E7%82%B9`;
  return new Response(btoa(node), {
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function createVlessSession(uuid, env) {
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
  let destination = "";

  server.addEventListener("message", (event) => {
    chain = chain.then(async () => {
      const chunk = toBytes(event.data);
      if (!initialized) {
        const header = parseVlessHeader(chunk, uuid);
        destination = header.hostname;
        await recordDiagnostic(env, destination, header.port, "header-ok");
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
        await recordDiagnostic(env, destination, header.port, "tcp-opened");
        writer = socket.writable.getWriter();
        writeChunk = (data) => writer.write(data);
        if (header.payload.byteLength) await writer.write(header.payload);
        initialized = true;
        void pipeRemote(socket, server, header.version, env, destination, header.port);
        return;
      }
      await writeChunk(chunk);
    }).catch(async (error) => {
      await recordDiagnostic(env, destination, 443, "client-error", error);
      closeSession(server, socket, writer);
    });
  });

  server.addEventListener("close", (event) => {
    void recordDiagnostic(env, destination, 443, "websocket-closed", undefined, {
      code: event.code,
      reason: String(event.reason || "").slice(0, 100),
    });
    closeSession(server, socket, writer);
  });
  server.addEventListener("error", () => {
    void recordDiagnostic(env, destination, 443, "websocket-error");
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

async function pipeRemote(socket, webSocket, version, env, destination, port) {
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
          await recordDiagnostic(env, destination, port, "first-response", undefined, {
            bytes: chunk.byteLength,
            prefix: Array.from(chunk.subarray(0, 8), (byte) => byte.toString(16).padStart(2, "0")).join(""),
          });
        } else {
          await sendWebSocket(webSocket, toArrayBuffer(chunk));
        }
      },
    }));
    await recordDiagnostic(env, destination, port, "remote-eof");
  } catch (error) {
    await recordDiagnostic(env, destination, port, "remote-error", error);
  } finally {
    if (firstChunk) await recordDiagnostic(env, destination, port, "closed-before-response");
    closeSession(webSocket, socket);
  }
}

async function recordDiagnostic(env, hostname, port, phase, error, extra = {}) {
  // Diagnostics were temporary. Do not consume production KV write quota.
  return;
  if (port !== 443) return;
  const normalized = String(hostname || "unknown").toLowerCase();
  const detail = error instanceof Error ? `${error.name}: ${error.message}` : "";
  await env.CONFIG.put(`diag:443:${normalized}`, JSON.stringify({
    destination: normalized,
    phase,
    detail: detail.slice(0, 300),
    time: new Date().toISOString(),
    ...extra,
  }), { expirationTtl: 3600 });
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
  const value = await env.CONFIG.get(key);
  if (!value) throw new Error(`${key} is required`);
  return value;
}

function constantTimeString(left, right) {
  const leftBytes = new TextEncoder().encode(left);
  const rightBytes = new TextEncoder().encode(right);
  if (leftBytes.byteLength !== rightBytes.byteLength) return false;
  let difference = 0;
  for (let index = 0; index < leftBytes.byteLength; index += 1) difference |= leftBytes[index] ^ rightBytes[index];
  return difference === 0;
}
