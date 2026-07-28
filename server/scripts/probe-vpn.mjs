import tls from "node:tls";
import { Duplex } from "node:stream";
import WebSocket from "ws";

const uuid = process.env.VPN_UUID;
const url = process.env.VPN_URL;
const hosts = (process.env.VPN_PROBE_HOSTS || "").split(",").map((host) => host.trim()).filter(Boolean);

if (!uuid || !url || !hosts.length) throw new Error("VPN_UUID, VPN_URL, and VPN_PROBE_HOSTS are required");

const uuidBytes = Uint8Array.from(uuid.replaceAll("-", "").match(/.{2}/g), (hex) => Number.parseInt(hex, 16));

async function probe(host) {
  return new Promise((resolve) => {
    const webSocket = new WebSocket(url);
    webSocket.binaryType = "arraybuffer";
    let prefixed = false;
    let tunnel;
    let client;
    let settled = false;
    const timeout = setTimeout(() => finish("timeout"), 12_000);

    const finish = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      try { client?.destroy(); } catch {}
      try { webSocket.close(); } catch {}
      resolve({ host, result });
    };

    webSocket.on("open", () => {
      tunnel = new Duplex({
        read() {},
        write(chunk, _encoding, callback) {
          if (!prefixed) {
            prefixed = true;
            const hostname = new TextEncoder().encode(host);
            const header = new Uint8Array(1 + 16 + 1 + 1 + 2 + 1 + 1 + hostname.length);
            let offset = 0;
            header[offset++] = 1;
            header.set(uuidBytes, offset); offset += 16;
            header[offset++] = 0;
            header[offset++] = 1;
            header[offset++] = 1; header[offset++] = 187;
            header[offset++] = 2;
            header[offset++] = hostname.length;
            header.set(hostname, offset);
            webSocket.send(Buffer.concat([header, chunk]), callback);
          } else {
            webSocket.send(chunk, callback);
          }
        },
      });
      client = tls.connect({ socket: tunnel, servername: host, ALPNProtocols: ["http/1.1"], rejectUnauthorized: true });
      client.setTimeout(10_000, () => finish("tls-timeout"));
      client.on("secureConnect", () => {
        client.write(`HEAD / HTTP/1.1\r\nHost: ${host}\r\nConnection: close\r\nUser-Agent: HJPLY-Diagnostic\r\n\r\n`);
      });
      let response = "";
      client.on("data", (chunk) => {
        response += chunk.toString("latin1");
        const status = response.match(/^HTTP\/1\.[01] (\d{3})/);
        if (status) finish(`HTTP ${status[1]}`);
      });
      client.on("error", (error) => finish(`tls-error: ${error.code || error.message}`));
    });

    webSocket.on("message", (message) => {
      let bytes = new Uint8Array(message);
      if (!tunnel) return;
      if (!tunnel.vlessResponseSeen) {
        if (bytes.byteLength < 2 || bytes[0] !== 1 || bytes[1] !== 0) return finish("invalid-vless-response");
        tunnel.vlessResponseSeen = true;
        bytes = bytes.subarray(2);
      }
      if (bytes.byteLength) tunnel.push(Buffer.from(bytes));
    });
    webSocket.on("error", (error) => finish(`websocket-error: ${error.message}`));
    webSocket.on("close", () => finish("connection-closed"));
  });
}

const results = await Promise.all(hosts.map(probe));
for (const { host, result } of results) console.log(`${host}\t${result}`);
if (results.some(({ result }) => !result.startsWith("HTTP "))) process.exitCode = 1;
