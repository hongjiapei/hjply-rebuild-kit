import WebSocket from "ws";

const uuid = process.env.VPN_UUID;
const url = process.env.VPN_URL;
const address = process.env.VPN_ADDRESS;

if (!uuid || !url) throw new Error("VPN_UUID and VPN_URL are required");

const uuidBytes = Uint8Array.from(uuid.replaceAll("-", "").match(/.{2}/g), (hex) => Number.parseInt(hex, 16));
const hostname = new TextEncoder().encode("www.google.com");
const payload = new TextEncoder().encode("GET / HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n");
const packet = new Uint8Array(1 + 16 + 1 + 1 + 2 + 1 + 1 + hostname.length + payload.length);
let offset = 0;
packet[offset++] = 1;
packet.set(uuidBytes, offset); offset += 16;
packet[offset++] = 0;
packet[offset++] = 1;
packet[offset++] = 0; packet[offset++] = 80;
packet[offset++] = 2;
packet[offset++] = hostname.length;
packet.set(hostname, offset); offset += hostname.length;
packet.set(payload, offset);

const options = address ? {
  lookup(_hostname, _options, callback) {
    const family = address.includes(":") ? 6 : 4;
    if (_options?.all) callback(null, [{ address, family }]);
    else callback(null, address, family);
  },
} : {};
const socket = new WebSocket(url, options);
socket.binaryType = "arraybuffer";
let first = true;
let response = "";
let succeeded = false;

const timeout = setTimeout(() => {
  socket.close();
  console.error("VPN smoke test timed out");
  process.exitCode = 1;
}, 15000);

socket.addEventListener("open", () => socket.send(packet));
socket.addEventListener("message", (event) => {
  let bytes = new Uint8Array(event.data);
  if (first) {
    if (bytes.byteLength < 2 || bytes[0] !== 1 || bytes[1] !== 0) throw new Error("Invalid VLESS response header");
    bytes = bytes.subarray(2);
    first = false;
  }
  response += new TextDecoder().decode(bytes, { stream: true });
  if (!succeeded && response.includes("HTTP/1.1")) {
    succeeded = true;
    clearTimeout(timeout);
    console.log(response.split("\r\n", 1)[0]);
    socket.close();
  }
});
socket.addEventListener("error", (event) => {
  clearTimeout(timeout);
  console.error(`WebSocket connection failed: ${event.error?.message || "unknown error"}`);
  process.exitCode = 1;
});
