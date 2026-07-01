// Central configuration, read once from the environment with sane defaults.
// Every value is optional; the defaults target the common "M1 mod on localhost:26000" case.

function intEnv(name: string, def: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === "") return def;
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) ? n : def;
}

function strEnv(name: string, def: string): string {
  const raw = process.env[name];
  return raw === undefined || raw.trim() === "" ? def : raw.trim();
}

export const config = {
  // Target socket to bridge.
  targetHost: strEnv("TARGET_HOST", "localhost"),
  targetPort: intEnv("TARGET_PORT", 26000),

  // HTTP server exposing /mcp and /health.
  bindHost: strEnv("BIND_HOST", "0.0.0.0"),
  port: intEnv("PORT", 26001),

  // Reconnect backoff to the target.
  reconnectMinMs: intEnv("RECONNECT_MIN_MS", 500),
  reconnectMaxMs: intEnv("RECONNECT_MAX_MS", 3000),

  // send_command reply collection.
  quietMs: intEnv("QUIET_MS", 300),
  sendTimeoutMs: intEnv("SEND_TIMEOUT_MS", 5000),

  // listen() blocking window.
  listenWindowMs: intEnv("LISTEN_WINDOW_MS", 30000),
} as const;

export const SERVICE_NAME = "mcp-minecraft";
export const SERVICE_VERSION = "0.1.0";
