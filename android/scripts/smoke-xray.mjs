import { spawn } from "node:child_process";
import { readFile, unlink, writeFile } from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const targetUrl = process.env.SMOKE_URL || "https://www.google.com/";
const expectedStatus = process.env.SMOKE_EXPECTED_STATUS || "200";
const properties = await readFile(path.join(root, "subscription.properties"), "utf8");
const subscriptionUrl = properties.match(/^SUBSCRIPTION_URL=(.+)$/m)?.[1]?.trim();
if (!subscriptionUrl) throw new Error("SUBSCRIPTION_URL is missing");

const subscription = await fetch(subscriptionUrl, { signal: AbortSignal.timeout(30_000) });
if (!subscription.ok) throw new Error(`Subscription HTTP ${subscription.status}`);
const [link] = Buffer.from((await subscription.text()).trim(), "base64").toString("utf8").split(/\r?\n/).filter(Boolean);
const node = new URL(link);
if (node.protocol !== "vless:") throw new Error("First subscription item is not VLESS");

const config = {
  log: { loglevel: "warning" },
  inbounds: [{ listen: "127.0.0.1", port: 10808, protocol: "socks", settings: { udp: false } }],
  outbounds: [{
    protocol: "vless",
    settings: { vnext: [{ address: node.hostname, port: Number(node.port), users: [{ id: node.username, encryption: "none" }] }] },
    streamSettings: {
      network: "ws",
      security: "tls",
      tlsSettings: { serverName: node.searchParams.get("sni"), fingerprint: "chrome" },
      wsSettings: { path: node.searchParams.get("path"), headers: { Host: node.searchParams.get("host") } },
    },
  }],
};
const configPath = path.join(os.tmpdir(), `hjply-xray-${process.pid}.json`);
const xray = path.join(root, "tools", "xray-test", "xray.exe");
await writeFile(configPath, JSON.stringify(config));
const processHandle = spawn(xray, ["run", "-c", configPath], { windowsHide: true });

try {
  await waitForPort(10808, 15_000);
  const curl = spawn("curl.exe", ["-sS", "-L", "-o", "NUL", "-w", "%{http_code} %{size_download}", "--max-time", "30", "--proxy", "socks5h://127.0.0.1:10808", targetUrl], { windowsHide: true });
  let output = "";
  let errors = "";
  curl.stdout.on("data", (chunk) => { output += chunk; });
  curl.stderr.on("data", (chunk) => { errors += chunk; });
  const exit = await new Promise((resolve) => curl.on("close", resolve));
  const [status, bytes] = output.trim().split(/\s+/, 2);
  console.log(`HTTP_STATUS=${status || "none"}`);
  console.log(`SIZE_DOWNLOAD=${bytes || "0"}`);
  console.log(`CURL_EXIT=${exit}`);
  if (errors.trim()) console.log(`CURL_ERROR=${errors.trim()}`);
  if (status !== expectedStatus) process.exitCode = 1;
} finally {
  processHandle.kill();
  await unlink(configPath).catch(() => {});
}

function waitForPort(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const socket = net.connect({ host: "127.0.0.1", port });
      socket.once("connect", () => { socket.destroy(); resolve(); });
      socket.once("error", () => {
        socket.destroy();
        if (Date.now() >= deadline) reject(new Error("Xray did not start"));
        else setTimeout(attempt, 200);
      });
    };
    attempt();
  });
}
