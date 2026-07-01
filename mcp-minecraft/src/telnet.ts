import net from "node:net";
import { config } from "./config.js";

// Sentinel strings returned by listen().
export const NO_DATA_KEEPALIVE = "NO-DATA-KEEPALIVE";
export const NO_CONNECTION_KEEPALIVE = "NO-CONNECTION-KEEPALIVE";

export interface TelnetStats {
  connected: boolean;
  host: string;
  port: number;
  connectedSince: string | null; // ISO timestamp of the current connection
  reconnects: number;            // number of times a connection has been (re)established
  attempts: number;              // total connect attempts (incl. refused)
  lastDataAt: string | null;     // ISO timestamp of last byte received
  lastError: string | null;      // last socket/connect error message
  bytesIn: number;
  bytesOut: number;
  queuedLines: number;           // lines buffered awaiting a consumer
}

/**
 * Persistent client for a newline-delimited text ("telnet") socket.
 *
 * - Continuously tries to connect to the target; when the port opens it connects
 *   automatically, and when the connection drops it reconnects with backoff.
 * - Splits the incoming byte stream into lines and buffers complete lines in a
 *   FIFO queue that both send_command replies and listen() drain from.
 * - The socket is treated as request/response (one command in, reply lines out),
 *   matching the M1 mod contract, but any unsolicited output is still captured
 *   and surfaced through listen().
 */
export class TelnetClient {
  private socket: net.Socket | null = null;
  private stopped = false;

  private partial = "";          // bytes not yet terminated by a newline
  private readonly inbound: string[] = [];
  private readonly waiters: Array<() => void> = [];

  private currentBackoff: number;

  private _connected = false;
  private _connectedSince: number | null = null;
  private _reconnects = 0;
  private _attempts = 0;
  private _lastDataAt: number | null = null;
  private _lastError: string | null = null;
  private _bytesIn = 0;
  private _bytesOut = 0;

  constructor(
    private readonly host = config.targetHost,
    private readonly port = config.targetPort,
  ) {
    this.currentBackoff = config.reconnectMinMs;
  }

  start(): void {
    this.stopped = false;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    if (this.socket) {
      this.socket.destroy();
      this.socket = null;
    }
  }

  get connected(): boolean {
    return this._connected;
  }

  stats(): TelnetStats {
    return {
      connected: this._connected,
      host: this.host,
      port: this.port,
      connectedSince: this._connectedSince ? new Date(this._connectedSince).toISOString() : null,
      reconnects: this._reconnects,
      attempts: this._attempts,
      lastDataAt: this._lastDataAt ? new Date(this._lastDataAt).toISOString() : null,
      lastError: this._lastError,
      bytesIn: this._bytesIn,
      bytesOut: this._bytesOut,
      queuedLines: this.inbound.length,
    };
  }

  private connect(): void {
    if (this.stopped) return;
    this._attempts++;

    // `host` may resolve to IPv4 or IPv6; using "localhost" (the default) lets an
    // IPv6-only listener be reached via ::1.
    const socket = net.createConnection({ host: this.host, port: this.port });
    this.socket = socket;

    socket.setNoDelay(true);
    // OS-level TCP keepalive: probe an idle connection so a silently dropped peer
    // is detected instead of hanging forever.
    socket.setKeepAlive(true, 15000);

    socket.on("connect", () => {
      this._connected = true;
      this._connectedSince = Date.now();
      this._reconnects++;
      this._lastError = null;
      this.currentBackoff = config.reconnectMinMs; // reset backoff on success
      this.partial = "";
    });

    socket.on("data", (chunk: Buffer) => {
      this._bytesIn += chunk.length;
      this._lastDataAt = Date.now();
      this.ingest(chunk.toString("utf8"));
    });

    socket.on("error", (err: Error) => {
      this._lastError = err.message;
    });

    socket.on("close", () => {
      this._connected = false;
      this._connectedSince = null;
      this.socket = null;
      this.scheduleReconnect();
    });
  }

  private scheduleReconnect(): void {
    if (this.stopped) return;
    const delay = this.currentBackoff;
    // Exponential backoff, capped. When the target port is simply not open yet,
    // this keeps retrying at the cap so we connect promptly once it appears.
    this.currentBackoff = Math.min(this.currentBackoff * 2, config.reconnectMaxMs);
    setTimeout(() => this.connect(), delay);
  }

  private ingest(text: string): void {
    this.partial += text;
    let idx: number;
    while ((idx = this.partial.indexOf("\n")) >= 0) {
      const line = this.partial.slice(0, idx).replace(/\r$/, "");
      this.partial = this.partial.slice(idx + 1);
      this.inbound.push(line);
    }
    if (this.inbound.length > 0) this.wakeWaiters();
  }

  private wakeWaiters(): void {
    const pending = this.waiters.splice(0);
    for (const w of pending) w();
  }

  // Resolve true as soon as a line is available, or false when timeoutMs elapses.
  private waitForData(timeoutMs: number): Promise<boolean> {
    if (this.inbound.length > 0) return Promise.resolve(true);
    return new Promise<boolean>((resolve) => {
      let done = false;
      const remove = () => {
        const i = this.waiters.indexOf(waiter);
        if (i >= 0) this.waiters.splice(i, 1);
      };
      const timer = setTimeout(() => {
        if (done) return;
        done = true;
        remove();
        resolve(false);
      }, Math.max(0, timeoutMs));
      const waiter = () => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        resolve(true);
      };
      this.waiters.push(waiter);
    });
  }

  private drainAll(): string[] {
    return this.inbound.splice(0);
  }

  /**
   * Collect lines using a "quiet period" heuristic: keep reading until no new
   * line arrives for `quietMs`, or until `maxMs` total elapses.
   */
  private async collect(quietMs: number, maxMs: number): Promise<string[]> {
    const out: string[] = [];
    const deadline = Date.now() + maxMs;
    out.push(...this.drainAll());
    while (Date.now() < deadline) {
      const remaining = deadline - Date.now();
      const got = await this.waitForData(Math.min(quietMs, remaining));
      if (!got) break; // no new data within the quiet window -> reply complete
      out.push(...this.drainAll());
    }
    return out;
  }

  /**
   * Send one command line and collect the reply.
   * Throws if there is no live connection to the target.
   */
  async sendCommand(
    command: string,
    quietMs: number = config.quietMs,
    timeoutMs: number = config.sendTimeoutMs,
  ): Promise<string> {
    if (!this._connected || !this.socket) {
      throw new Error(
        `Not connected to target ${this.host}:${this.port}. The target program may not be running yet.`,
      );
    }
    const payload = command.endsWith("\n") ? command : command + "\n";
    const buf = Buffer.from(payload, "utf8");
    this.socket.write(buf);
    this._bytesOut += buf.length;

    const lines = await this.collect(quietMs, timeoutMs);
    return lines.join("\n");
  }

  /**
   * Block up to `windowMs` for output from the target.
   * - If output arrives, drain the burst and return it.
   * - If nothing arrives while connected, return NO-DATA-KEEPALIVE.
   * - If not connected to the target, return NO-CONNECTION-KEEPALIVE.
   * Either sentinel keeps the MCP transport active.
   */
  async listen(windowMs: number = config.listenWindowMs): Promise<string> {
    if (!this._connected) return NO_CONNECTION_KEEPALIVE;
    const got = await this.waitForData(windowMs);
    if (!got) return NO_DATA_KEEPALIVE;
    const lines = await this.collect(config.quietMs, 2000);
    const text = lines.join("\n");
    return text.length > 0 ? text : NO_DATA_KEEPALIVE;
  }
}
