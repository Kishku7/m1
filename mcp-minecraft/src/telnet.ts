import net from "node:net";
import { config } from "./config.js";

// Sentinel strings returned by listen().
export const NO_DATA_KEEPALIVE = "NO-DATA-KEEPALIVE";
export const NO_CONNECTION_KEEPALIVE = "NO-CONNECTION-KEEPALIVE";

export interface TelnetStats {
  connected: boolean;            // TCP socket established
  ready: boolean;                // connected AND primed (safe to send commands)
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
 * - Continuously tries to connect; when the port opens it connects automatically,
 *   and when the connection drops it reconnects with capped backoff.
 * - On connect it can send an init line (config.connectInit, e.g. "RAW ON") and
 *   drain the connect banner (config.discardBanner) before becoming "ready".
 * - Splits the byte stream into lines buffered in a FIFO that send_command and
 *   listen() drain from.
 * - Reply framing: if config.replySentinel is set, send_command reads up to a line
 *   equal to the sentinel (e.g. M1's "<<END"); otherwise it uses quiet-period timing.
 */
export class TelnetClient {
  private socket: net.Socket | null = null;
  private stopped = false;

  private partial = "";
  private readonly inbound: string[] = [];
  private readonly waiters: Array<() => void> = [];

  private currentBackoff: number;

  private _socketUp = false;
  private _ready = false;
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
    return this._socketUp;
  }

  get ready(): boolean {
    return this._ready;
  }

  stats(): TelnetStats {
    return {
      connected: this._socketUp,
      ready: this._ready,
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

    const socket = net.createConnection({ host: this.host, port: this.port });
    this.socket = socket;

    socket.setNoDelay(true);
    socket.setKeepAlive(true, 15000);

    socket.on("connect", () => {
      this._socketUp = true;
      this._ready = false;
      this._connectedSince = Date.now();
      this._reconnects++;
      this._lastError = null;
      this.currentBackoff = config.reconnectMinMs;
      this.partial = "";
      this.inbound.length = 0;
      void this.prime(socket);
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
      this._socketUp = false;
      this._ready = false;
      this._connectedSince = null;
      this.socket = null;
      this.scheduleReconnect();
    });
  }

  // On connect: optionally send an init line, optionally drain the banner, then ready.
  private async prime(socket: net.Socket): Promise<void> {
    const doInit = config.connectInit.length > 0;
    const doDrain = config.discardBanner || doInit;

    if (doInit) {
      const buf = Buffer.from(config.connectInit + "\n", "utf8");
      socket.write(buf);
      this._bytesOut += buf.length;
    }

    if (doDrain) {
      const deadline = Date.now() + 5000;
      while (Date.now() < deadline) {
        const remaining = deadline - Date.now();
        const got = await this.waitForData(Math.min(config.primeQuietMs, remaining));
        if (!got) break; // quiet -> banner/init drained
        this.inbound.length = 0; // discard
      }
      this.inbound.length = 0;
      this.partial = "";
    }

    if (this._socketUp && this.socket === socket) this._ready = true;
  }

  private scheduleReconnect(): void {
    if (this.stopped) return;
    const delay = this.currentBackoff;
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

  // Quiet-period reply collection (generic mode).
  private async collect(quietMs: number, maxMs: number): Promise<string[]> {
    const out: string[] = [];
    const deadline = Date.now() + maxMs;
    out.push(...this.drainAll());
    while (Date.now() < deadline) {
      const remaining = deadline - Date.now();
      const got = await this.waitForData(Math.min(quietMs, remaining));
      if (!got) break;
      out.push(...this.drainAll());
    }
    return out;
  }

  // Sentinel reply collection: read lines until one equals `sentinel`; leftover
  // lines after it stay in the FIFO for the next read.
  private async readUntilSentinel(sentinel: string, maxMs: number): Promise<string> {
    const out: string[] = [];
    const deadline = Date.now() + maxMs;
    while (Date.now() < deadline) {
      while (this.inbound.length > 0) {
        const line = this.inbound.shift() as string;
        if (line === sentinel) return out.join("\n");
        out.push(line);
      }
      const remaining = deadline - Date.now();
      const got = await this.waitForData(remaining);
      if (!got) break;
    }
    const warn = `(warning: reply not terminated by '${sentinel}' within ${maxMs}ms)`;
    return out.length > 0 ? out.join("\n") + "\n" + warn : warn;
  }

  /**
   * Send one command line and collect the reply.
   * Throws if there is no live, primed connection to the target.
   */
  async sendCommand(command: string, timeoutMs: number = config.sendTimeoutMs): Promise<string> {
    if (!this._socketUp || !this.socket) {
      throw new Error(
        `Not connected to target ${this.host}:${this.port}. The target program may not be running yet.`,
      );
    }
    if (!this._ready) {
      throw new Error(`Connection to ${this.host}:${this.port} is still initializing; retry shortly.`);
    }
    const payload = command.endsWith("\n") ? command : command + "\n";
    const buf = Buffer.from(payload, "utf8");
    this.socket.write(buf);
    this._bytesOut += buf.length;

    if (config.replySentinel.length > 0) {
      return this.readUntilSentinel(config.replySentinel, timeoutMs);
    }
    const lines = await this.collect(config.quietMs, timeoutMs);
    return lines.join("\n");
  }

  /**
   * Block up to `windowMs` for output from the target.
   * - output, if any arrived;
   * - NO-DATA-KEEPALIVE if connected but idle;
   * - NO-CONNECTION-KEEPALIVE if not connected/ready.
   */
  async listen(windowMs: number = config.listenWindowMs): Promise<string> {
    if (!this._socketUp || !this._ready) return NO_CONNECTION_KEEPALIVE;
    const got = await this.waitForData(windowMs);
    if (!got) return NO_DATA_KEEPALIVE;
    const lines = await this.collect(config.quietMs, 2000);
    const text = lines.filter((l) => l !== config.replySentinel).join("\n");
    return text.length > 0 ? text : NO_DATA_KEEPALIVE;
  }
}
