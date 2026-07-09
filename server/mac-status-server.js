#!/usr/bin/env node

const crypto = require("node:crypto");
const dns = require("node:dns").promises;
const fs = require("node:fs");
const http = require("node:http");
const https = require("node:https");
const os = require("node:os");
const path = require("node:path");
const { execFile } = require("node:child_process");
const { promisify } = require("node:util");

const execFileAsync = promisify(execFile);

const PORT = Number.parseInt(process.env.MAC_STATUS_PORT || "5178", 10);
const HOST = process.env.MAC_STATUS_HOST || "0.0.0.0";
const TOKEN_FILE = path.join(process.cwd(), ".mac-status-token");
const MANIFEST_FILE = path.join(process.cwd(), "public", "update-manifest.json");
const CLIENTS_FILE = path.join(process.cwd(), ".mac-status-clients.json");
const VERSION = "1.3.3";

let lastCpuSnapshot = readCpuSnapshot();
let cachedInternet = null;
let cachedInternetAt = 0;

function ensureToken() {
  if (process.env.MAC_STATUS_TOKEN && process.env.MAC_STATUS_TOKEN.trim()) {
    return process.env.MAC_STATUS_TOKEN.trim();
  }

  if (fs.existsSync(TOKEN_FILE)) {
    return fs.readFileSync(TOKEN_FILE, "utf8").trim();
  }

  const token = crypto.randomBytes(18).toString("base64url");
  fs.writeFileSync(TOKEN_FILE, `${token}\n`, { mode: 0o600 });
  return token;
}

const TOKEN = ensureToken();

function readCpuSnapshot() {
  const cpus = os.cpus();
  const totals = cpus.reduce(
    (acc, cpu) => {
      acc.idle += cpu.times.idle;
      acc.total += Object.values(cpu.times).reduce((sum, value) => sum + value, 0);
      return acc;
    },
    { idle: 0, total: 0 }
  );
  return totals;
}

function sampleCpuPercent() {
  const current = readCpuSnapshot();
  const idleDiff = current.idle - lastCpuSnapshot.idle;
  const totalDiff = current.total - lastCpuSnapshot.total;
  lastCpuSnapshot = current;

  if (totalDiff <= 0) {
    const estimate = (os.loadavg()[0] / Math.max(os.cpus().length, 1)) * 100;
    return clampPercent(estimate);
  }

  return clampPercent((1 - idleDiff / totalDiff) * 100);
}

function clampPercent(value) {
  return Number(Math.max(0, Math.min(100, value)).toFixed(1));
}

async function readBattery() {
  try {
    const { stdout } = await execFileAsync("pmset", ["-g", "batt"], { timeout: 2500 });
    const percentMatch = stdout.match(/(\d+)%/);
    const percent = percentMatch ? Number.parseInt(percentMatch[1], 10) : null;
    const lower = stdout.toLowerCase();
    const source = stdout.includes("'AC Power'") ? "AC Power" : stdout.includes("'Battery Power'") ? "Battery Power" : "Unknown";
    const timeMatch = stdout.match(/(\d+:\d+)\s+remaining/i);

    return {
      present: percent !== null,
      percent,
      charging: lower.includes("charging") || source === "AC Power",
      source,
      timeRemaining: timeMatch ? timeMatch[1] : null,
      raw: stdout.trim().replace(/\s+/g, " ")
    };
  } catch (error) {
    return {
      present: false,
      percent: null,
      charging: false,
      source: "Unknown",
      timeRemaining: null,
      raw: error.message
    };
  }
}

async function readMemory() {
  const totalBytes = os.totalmem();

  try {
    const { stdout } = await execFileAsync("vm_stat", [], { timeout: 2000 });
    const pageSizeMatch = stdout.match(/page size of (\d+) bytes/i);
    const pageSize = pageSizeMatch ? Number.parseInt(pageSizeMatch[1], 10) : 4096;
    const pages = {};

    stdout.split("\n").forEach((line) => {
      const match = line.match(/^([^:]+):\s+([\d.]+)/);
      if (match) {
        pages[match[1].trim()] = Number.parseInt(match[2].replace(/\./g, ""), 10);
      }
    });

    const availablePages =
      (pages["Pages free"] || 0) +
      (pages["Pages inactive"] || 0) +
      (pages["Pages speculative"] || 0) +
      (pages["Pages purgeable"] || 0);
    const availableBytes = Math.min(totalBytes, availablePages * pageSize);
    const usedBytes = Math.max(0, totalBytes - availableBytes);

    return {
      totalBytes,
      freeBytes: availableBytes,
      usedBytes,
      percentUsed: clampPercent((usedBytes / Math.max(totalBytes, 1)) * 100),
      source: "vm_stat"
    };
  } catch (error) {
    const freeBytes = os.freemem();
    const usedBytes = Math.max(0, totalBytes - freeBytes);
    return {
      totalBytes,
      freeBytes,
      usedBytes,
      percentUsed: clampPercent((usedBytes / Math.max(totalBytes, 1)) * 100),
      source: "os.freemem",
      error: error.message
    };
  }
}

function cleanCommand(command) {
  const value = String(command || "").trim();
  if (!value) {
    return "Unknown";
  }
  const first = value.split(/\s+/)[0] || value;
  return path.basename(first);
}

async function readProcesses() {
  try {
    const { stdout } = await execFileAsync("ps", ["-axo", "pid,pcpu,pmem,comm", "-r"], { timeout: 2500 });
    const rows = stdout
      .trim()
      .split("\n")
      .slice(1)
      .map((line) => {
        const match = line.trim().match(/^(\d+)\s+([\d.]+)\s+([\d.]+)\s+(.+)$/);
        if (!match) {
          return null;
        }
        return {
          pid: Number.parseInt(match[1], 10),
          cpuPercent: Number.parseFloat(Number.parseFloat(match[2]).toFixed(1)),
          memoryPercent: Number.parseFloat(Number.parseFloat(match[3]).toFixed(1)),
          name: cleanCommand(match[4]),
          command: match[4].trim()
        };
      })
      .filter(Boolean);

    return {
      topCpu: rows
        .slice()
        .sort((a, b) => b.cpuPercent - a.cpuPercent)
        .slice(0, 5),
      topMemory: rows
        .slice()
        .sort((a, b) => b.memoryPercent - a.memoryPercent)
        .slice(0, 5),
      source: "ps"
    };
  } catch (error) {
    return {
      topCpu: [],
      topMemory: [],
      source: "ps",
      error: error.message
    };
  }
}

function networkAddresses() {
  const interfaces = os.networkInterfaces();
  const addresses = [];

  Object.entries(interfaces).forEach(([name, entries]) => {
    (entries || []).forEach((entry) => {
      if (entry.family === "IPv4" && !entry.internal) {
        addresses.push({ name, address: entry.address });
      }
    });
  });

  return addresses;
}

async function stableEndpoints() {
  const endpoints = [];
  const configuredUrl = String(process.env.MAC_STATUS_PUBLIC_URL || "").trim();
  if (configuredUrl) {
    endpoints.push({
      type: "configured",
      label: "Configured public URL",
      url: configuredUrl,
      note: "Set with MAC_STATUS_PUBLIC_URL."
    });
  }

  try {
    const { stdout } = await execFileAsync("tailscale", ["status", "--json"], { timeout: 2500 });
    const status = JSON.parse(stdout);
    const dnsName = String(status.Self && status.Self.DNSName ? status.Self.DNSName : "").replace(/\.$/, "");
    if (dnsName) {
      endpoints.push({
        type: "tailscale-magicdns",
        label: "Tailscale MagicDNS",
        url: `http://${dnsName}:${PORT}`,
        note: "Requires Tailscale on the phone and Mac, signed into the same tailnet."
      });
    }
  } catch (_error) {
    // Tailscale is optional.
  }

  try {
    const { stdout } = await execFileAsync("tailscale", ["ip", "-4"], { timeout: 2500 });
    const ip = stdout.trim().split(/\s+/)[0];
    if (ip) {
      endpoints.push({
        type: "tailscale-ip",
        label: "Tailscale IP",
        url: `http://${ip}:${PORT}`,
        note: "Stable while Tailscale is installed; MagicDNS is easier to read when available."
      });
    }
  } catch (_error) {
    // Tailscale is optional.
  }

  return endpoints;
}

function httpsProbe(url) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const request = https.get(url, { timeout: 3000 }, (response) => {
      response.resume();
      response.on("end", () => {
        resolve({ latencyMs: Date.now() - started, statusCode: response.statusCode });
      });
    });

    request.on("timeout", () => {
      request.destroy(new Error("internet probe timed out"));
    });

    request.on("error", reject);
  });
}

function withTimeout(promise, ms, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms);
  });

  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

async function readInternet() {
  const now = Date.now();
  if (cachedInternet && now - cachedInternetAt < 10000) {
    return cachedInternet;
  }

  const started = Date.now();
  try {
    await withTimeout(dns.lookup("apple.com"), 1200, "DNS lookup");
    const probe = await withTimeout(
      httpsProbe("https://www.apple.com/library/test/success.html"),
      2200,
      "internet probe"
    );
    cachedInternet = {
      online: probe.statusCode >= 200 && probe.statusCode < 500,
      latencyMs: Date.now() - started,
      checkedAt: new Date().toISOString(),
      error: null
    };
  } catch (error) {
    cachedInternet = {
      online: false,
      latencyMs: null,
      checkedAt: new Date().toISOString(),
      error: error.message
    };
  }

  cachedInternetAt = now;
  return cachedInternet;
}

function summarize(status) {
  const messages = [];
  let level = "ok";

  if (status.battery.present && status.battery.percent !== null && status.battery.percent <= 20 && !status.battery.charging) {
    level = "critical";
    messages.push(`Battery is low at ${status.battery.percent}%.`);
  } else if (status.battery.present && status.battery.percent !== null && status.battery.percent <= 35 && !status.battery.charging) {
    level = "warning";
    messages.push(`Battery is at ${status.battery.percent}%.`);
  }

  if (status.cpu.percent >= 90) {
    level = level === "critical" ? level : "warning";
    messages.push(`CPU is busy at ${status.cpu.percent}%.`);
  }

  if (status.memory.percentUsed >= 90) {
    level = "critical";
    messages.push(`RAM usage is high at ${status.memory.percentUsed}%.`);
  } else if (status.memory.percentUsed >= 80) {
    level = level === "critical" ? level : "warning";
    messages.push(`RAM usage is elevated at ${status.memory.percentUsed}%.`);
  }

  if (!status.internet.online) {
    level = "critical";
    messages.push("Internet probe failed.");
  }

  if (messages.length === 0) {
    messages.push("MacBook looks healthy.");
  }

  return { level, messages };
}

async function buildStatus() {
  const [battery, internet, memory, processes, stable] = await Promise.all([
    readBattery(),
    readInternet(),
    readMemory(),
    readProcesses(),
    stableEndpoints()
  ]);
  const status = {
    app: "Mac Status Link",
    version: VERSION,
    hostname: os.hostname(),
    timestamp: new Date().toISOString(),
    uptimeSeconds: Math.floor(os.uptime()),
    cpu: {
      percent: sampleCpuPercent(),
      cores: os.cpus().length,
      loadAverage1m: Number(os.loadavg()[0].toFixed(2)),
      model: os.cpus()[0] ? os.cpus()[0].model : "Unknown"
    },
    memory,
    battery,
    internet,
    network: {
      addresses: networkAddresses(),
      stableEndpoints: stable,
      recommendedEndpoint: stable.length ? stable[0].url : null
    },
    processes,
    server: {
      host: HOST,
      port: PORT
    }
  };

  status.analysis = summarize(status);
  return status;
}

function sendJson(response, statusCode, payload) {
  const body = JSON.stringify(payload, null, 2);
  response.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "authorization, content-type, x-mac-status-token",
    "Access-Control-Allow-Methods": "GET, OPTIONS"
  });
  response.end(body);
}

function recordClient(request) {
  const forwarded = String(request.headers["x-forwarded-for"] || "").split(",")[0].trim();
  const remote = forwarded || request.socket.remoteAddress || "unknown";
  const client = {
    remote,
    userAgent: request.headers["user-agent"] || "unknown",
    lastSeen: new Date().toISOString()
  };

  let existing = [];
  try {
    existing = JSON.parse(fs.readFileSync(CLIENTS_FILE, "utf8"));
    if (!Array.isArray(existing)) {
      existing = [];
    }
  } catch (_error) {
    existing = [];
  }

  const filtered = existing.filter((entry) => entry.remote !== client.remote);
  filtered.unshift(client);
  fs.writeFileSync(CLIENTS_FILE, JSON.stringify(filtered.slice(0, 20), null, 2));
}

function tokenFrom(request, parsedUrl) {
  const auth = request.headers.authorization || "";
  if (auth.toLowerCase().startsWith("bearer ")) {
    return auth.slice(7).trim();
  }
  if (request.headers["x-mac-status-token"]) {
    return String(request.headers["x-mac-status-token"]).trim();
  }
  return parsedUrl.searchParams.get("token") || "";
}

function authorized(request, parsedUrl) {
  return tokenFrom(request, parsedUrl) === TOKEN;
}

function sendHome(response) {
  response.writeHead(200, {
    "Content-Type": "text/html; charset=utf-8",
    "Cache-Control": "no-store"
  });
  response.end(`<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Mac Status Link</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 32px; color: #17202a; background: #f7f9fb; }
    main { max-width: 760px; margin: 0 auto; background: white; border: 1px solid #d7dee8; border-radius: 8px; padding: 24px; }
    code { background: #eef2f7; padding: 2px 5px; border-radius: 4px; }
    li { margin: 8px 0; }
  </style>
</head>
<body>
  <main>
    <h1>Mac Status Link</h1>
    <p>This MacBook status service is running.</p>
    <ul>
      <li>Phone endpoint: <code>http://YOUR_MAC_IP:${PORT}</code></li>
      <li>Token file: <code>${TOKEN_FILE}</code></li>
      <li>JSON endpoint: <code>/api/status</code></li>
      <li>Live stream endpoint: <code>/api/stream</code></li>
    </ul>
    <p>Use the Android app with the endpoint above and the token from <code>.mac-status-token</code>.</p>
  </main>
</body>
</html>`);
}

const server = http.createServer(async (request, response) => {
  const parsedUrl = new URL(request.url, `http://${request.headers.host || `localhost:${PORT}`}`);

  if (request.method === "OPTIONS") {
    response.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "authorization, content-type, x-mac-status-token",
      "Access-Control-Allow-Methods": "GET, OPTIONS"
    });
    response.end();
    return;
  }

  if (parsedUrl.pathname === "/") {
    sendHome(response);
    return;
  }

  if (!authorized(request, parsedUrl)) {
    sendJson(response, 401, { error: "Unauthorized. Use the token from .mac-status-token." });
    return;
  }

  if (parsedUrl.pathname === "/api/status") {
    try {
      recordClient(request);
      sendJson(response, 200, await buildStatus());
    } catch (error) {
      sendJson(response, 500, { error: error.message });
    }
    return;
  }

  if (parsedUrl.pathname === "/api/manifest") {
    try {
      const manifest = JSON.parse(fs.readFileSync(MANIFEST_FILE, "utf8"));
      sendJson(response, 200, manifest);
    } catch (error) {
      sendJson(response, 404, { error: "Update manifest is not available.", detail: error.message });
    }
    return;
  }

  if (parsedUrl.pathname === "/api/stream") {
    response.writeHead(200, {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-store",
      "Connection": "keep-alive",
      "Access-Control-Allow-Origin": "*"
    });

    let closed = false;
    request.on("close", () => {
      closed = true;
    });

    const writeStatus = async () => {
      if (closed) {
        return;
      }
      try {
        const status = await buildStatus();
        response.write(`event: status\ndata: ${JSON.stringify(status)}\n\n`);
      } catch (error) {
        response.write(`event: error\ndata: ${JSON.stringify({ error: error.message })}\n\n`);
      }
    };

    await writeStatus();
    const timer = setInterval(writeStatus, 2000);
    request.on("close", () => clearInterval(timer));
    return;
  }

  sendJson(response, 404, { error: "Not found" });
});

server.listen(PORT, HOST, () => {
  const addresses = networkAddresses().map((entry) => `http://${entry.address}:${PORT}`).join(", ");
  console.log(`Mac Status Link ${VERSION} listening on ${HOST}:${PORT}`);
  console.log(`Token: ${TOKEN}`);
  console.log(`Phone endpoint candidates: ${addresses || `http://localhost:${PORT}`}`);
});
