import express, { type Request, type Response } from "express";
import { randomUUID } from "node:crypto";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { isInitializeRequest } from "@modelcontextprotocol/sdk/types.js";
import { TelnetClient } from "./telnet.js";
import { createMcpServer } from "./mcp.js";
import { config, SERVICE_NAME, SERVICE_VERSION } from "./config.js";

const startedAt = Date.now();

// Single shared connection to the target socket.
const telnet = new TelnetClient();
telnet.start();

// Active MCP transports keyed by session id.
const transports: Record<string, StreamableHTTPServerTransport> = {};

const app = express();
app.use(express.json());

// ---- Health endpoint (no auth) --------------------------------------------
app.get("/health", (_req: Request, res: Response) => {
  const s = telnet.stats();
  res.json({
    status: "ok",
    service: SERVICE_NAME,
    version: SERVICE_VERSION,
    uptime_seconds: Math.round((Date.now() - startedAt) / 1000),
    target: {
      host: s.host,
      port: s.port,
      connected: s.connected,
      ready: s.ready,
      connected_since: s.connectedSince,
      reconnects: s.reconnects,
      attempts: s.attempts,
      last_data_at: s.lastDataAt,
      last_error: s.lastError,
      bytes_in: s.bytesIn,
      bytes_out: s.bytesOut,
      queued_lines: s.queuedLines,
    },
    mcp: { active_sessions: Object.keys(transports).length },
    timestamp: new Date().toISOString(),
  });
});

// ---- MCP endpoint (Streamable HTTP, stateful sessions, no auth) ------------
app.post("/mcp", async (req: Request, res: Response) => {
  const sessionId = req.headers["mcp-session-id"] as string | undefined;
  let transport: StreamableHTTPServerTransport | undefined =
    sessionId ? transports[sessionId] : undefined;

  if (!transport) {
    if (sessionId || !isInitializeRequest(req.body)) {
      res.status(400).json({
        jsonrpc: "2.0",
        error: { code: -32000, message: "Bad Request: no valid session ID" },
        id: null,
      });
      return;
    }
    // New session: create a transport + a fresh MCP server bound to the shared telnet.
    transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: () => randomUUID(),
      onsessioninitialized: (sid: string) => {
        transports[sid] = transport as StreamableHTTPServerTransport;
      },
    });
    transport.onclose = () => {
      if (transport && transport.sessionId) delete transports[transport.sessionId];
    };
    const server = createMcpServer(telnet);
    await server.connect(transport);
  }

  await transport.handleRequest(req, res, req.body);
});

// GET (server->client SSE stream) and DELETE (session teardown).
async function handleSessionRequest(req: Request, res: Response): Promise<void> {
  const sessionId = req.headers["mcp-session-id"] as string | undefined;
  const transport = sessionId ? transports[sessionId] : undefined;
  if (!transport) {
    res.status(400).send("Invalid or missing session ID");
    return;
  }
  await transport.handleRequest(req, res);
}
app.get("/mcp", handleSessionRequest);
app.delete("/mcp", handleSessionRequest);

const httpServer = app.listen(config.port, config.bindHost, () => {
  console.log(
    `[${SERVICE_NAME}] v${SERVICE_VERSION} listening on http://${config.bindHost}:${config.port}`,
  );
  console.log(`[${SERVICE_NAME}]   MCP endpoint : http://${config.bindHost}:${config.port}/mcp`);
  console.log(`[${SERVICE_NAME}]   health       : http://${config.bindHost}:${config.port}/health`);
  console.log(`[${SERVICE_NAME}]   target       : ${config.targetHost}:${config.targetPort}`);
});
// Keep idle HTTP/SSE connections open longer than the default 5s.
httpServer.keepAliveTimeout = 120000;
httpServer.headersTimeout = 125000;

function shutdown(signal: string): void {
  console.log(`[${SERVICE_NAME}] ${signal} received, shutting down`);
  telnet.stop();
  httpServer.close(() => process.exit(0));
  // Failsafe if connections linger.
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));
