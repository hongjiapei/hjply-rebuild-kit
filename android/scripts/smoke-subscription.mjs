import { spawn } from "node:child_process";
import { readFile, unlink, writeFile } from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";

const projectRoot = path.resolve(import.meta.dirname, "..");
const executable = path.join(projectRoot, "tools", "sing-box.exe");
const properties = await readFile(path.join(projectRoot, "subscription.properties"), "utf8");
const configuredUrl = properties.match(/^SUBSCRIPTION_URL=(.+)$/m)?.[1]?.trim();
if (!configuredUrl) throw new Error("SUBSCRIPTION_URL is missing");

const subscriptionUrl = configuredUrl.replace("/sub?", "/sub.json?");
const targetUrl = process.env.SMOKE_URL || "https://www.google.com/generate_204";
const expectedStatus = process.env.SMOKE_EXPECTED_STATUS || "204";
const response = await fetch(subscriptionUrl, { signal: AbortSignal.timeout(30_000) });
if (!response.ok) throw new Error(`Subscription HTTP ${response.status}`);
const config = await response.json();
config.inbounds = [{ type: "mixed", tag: "mixed-in", listen: "127.0.0.1", listen_port: 2080 }];

const configPath = path.join(os.tmpdir(), `hjply-smoke-${process.pid}.json`);
await writeFile(configPath, JSON.stringify(config));
const logs = [];
const child = spawn(executable, ["run", "-c", configPath], { windowsHide: true });
child.stdout.on("data", (chunk) => logs.push(String(chunk)));
child.stderr.on("data", (chunk) => logs.push(String(chunk)));

try {
  await waitForPort(2080, 15_000);
  const curl = spawn("curl.exe", [
    "-sS",
    "-o", "NUL",
    "-w", "%{http_code} %{size_download}",
    "--max-time", "20",
    "--proxy", "socks5h://127.0.0.1:2080",
    targetUrl,
  ], { windowsHide: true });
  let status = "";
  let curlError = "";
  curl.stdout.on("data", (chunk) => { status += chunk; });
  curl.stderr.on("data", (chunk) => { curlError += chunk; });
  const exitCode = await new Promise((resolve) => curl.on("close", resolve));
  const [httpStatus, sizeDownload] = status.trim().split(/\s+/, 2);
  console.log(`HTTP_STATUS=${httpStatus || "none"}`);
  console.log(`SIZE_DOWNLOAD=${sizeDownload || "0"}`);
  console.log(`CURL_EXIT=${exitCode}`);
  if (curlError.trim()) console.log(curlError.trim());
  if (httpStatus !== expectedStatus) {
    console.log("--- sing-box log ---");
    console.log(logs.join("").replaceAll(/\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\b/gi, "<redacted-uuid>").trim());
    process.exitCode = 1;
  }
} finally {
  child.kill();
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
        if (Date.now() >= deadline) reject(new Error("sing-box did not start"));
        else setTimeout(attempt, 200);
      });
    };
    attempt();
  });
}
