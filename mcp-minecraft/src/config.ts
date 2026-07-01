// Central configuration, read once from the environment with sane defaults.
// Defaults keep the server generic (quiet-period reply framing, no banner handling).
// For the M1 mod, set REPLY_SENTINEL=<<END, CONNECT_INIT="RAW ON", DISCARD_CONNECT_BANNER=true.

function intEnv(name: string, def: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === "") return def;
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) ? n : def;
}

function strEnv(name: string, def: string): string {
  const raw = process.env[name];
  return raw === undefined ? def : raw;
}

function boolEnv(name: string, def: boolean): boolean {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === "") return def;
  return /^(1|true|yes|on)$/i.test(raw.trim());
}

export const config = {
  // Target socket to bridge.
  targetHost: strEnv("TARGET_HOST", "localhost").trim(),
  targetPort: intEnv("TARGET_PORT", 26000),

  // HTTP server exposing /mcp and /health.
  bindHost: strEnv("BIND_HOST", "0.0.0.0").trim(),
  port: intEnv("PORT", 26001),

  // Reconnect backoff to the target.
  reconnectMinMs: intEnv("RECONNECT_MIN_MS", 500),
  reconnectMaxMs: intEnv("RECONNECT_MAX_MS", 3000),

  // Reply framing. If REPLY_SENTINEL is set, send_command reads until a line that
  // exactly equals it (deterministic; e.g. M1's "<<END"). If empty, replies are
  // collected by quiet-period timing (generic default).
  replySentinel: strEnv("REPLY_SENTINEL", ""),
  quietMs: intEnv("QUIET_MS", 300),
  sendTimeoutMs: intEnv("SEND_TIMEOUT_MS", 15000),

  // On connect: optionally send an init line (e.g. "RAW ON"), and optionally
  // drain+discard whatever the target emits on connect (greeting/banner + the
  // init reply) so it does not pollute the first command's reply.
  connectInit: strEnv("CONNECT_INIT", ""),
  discardBanner: boolEnv("DISCARD_CONNECT_BANNER", false),
  primeQuietMs: intEnv("PRIME_QUIET_MS", 400),

  // listen() blocking window.
  listenWindowMs: intEnv("LISTEN_WINDOW_MS", 30000),
} as const;

export const SERVICE_NAME = "mcp-minecraft";
export const SERVICE_VERSION = "0.2.0";
