import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { TelnetClient } from "./telnet.js";
import { config, SERVICE_NAME, SERVICE_VERSION } from "./config.js";

// Build a fresh MCP server instance (one per transport/session) whose tools all
// operate on the single shared TelnetClient.
export function createMcpServer(telnet: TelnetClient): McpServer {
  const server = new McpServer(
    { name: SERVICE_NAME, version: SERVICE_VERSION },
    {
      instructions:
        "Bridges a newline-delimited TCP text socket (e.g. the M1 Minecraft mod on " +
        "localhost:26000) to you. Use send_command to send one line and read the reply. " +
        "Use listen to poll for asynchronous output and to keep the connection warm. " +
        "Use connection_status to check whether the target socket is connected and ready.",
    },
  );

  server.registerTool(
    "send_command",
    {
      title: "Send command",
      description:
        "Write one command line to the target socket and return its reply. If a reply " +
        "sentinel is configured (e.g. M1's <<END) the reply is read exactly up to it; " +
        "otherwise it is collected until the socket goes quiet. Errors if the target is " +
        "not connected/ready.",
      inputSchema: {
        command: z.string().describe("The command line to send (a trailing newline is added automatically)."),
        timeout_ms: z
          .number()
          .int()
          .positive()
          .optional()
          .describe(`Overall cap for collecting the reply in ms (default ${config.sendTimeoutMs}).`),
      },
    },
    async ({ command, timeout_ms }) => {
      try {
        const reply = await telnet.sendCommand(command, timeout_ms);
        return { content: [{ type: "text", text: reply.length > 0 ? reply : "(no reply)" }] };
      } catch (err) {
        return {
          isError: true,
          content: [{ type: "text", text: err instanceof Error ? err.message : String(err) }],
        };
      }
    },
  );

  server.registerTool(
    "listen",
    {
      title: "Listen for output",
      description:
        "Block for up to window_ms waiting for output from the target. Returns the output if " +
        "any arrives; otherwise returns NO-DATA-KEEPALIVE (connected, idle) or " +
        "NO-CONNECTION-KEEPALIVE (target not connected/ready). Call it repeatedly to stream " +
        "asynchronous output and to keep the connection alive during quiet periods.",
      inputSchema: {
        window_ms: z
          .number()
          .int()
          .positive()
          .optional()
          .describe(`How long to wait for output before returning a keepalive (default ${config.listenWindowMs}).`),
      },
    },
    async ({ window_ms }) => {
      const text = await telnet.listen(window_ms);
      return { content: [{ type: "text", text }] };
    },
  );

  server.registerTool(
    "connection_status",
    {
      title: "Connection status",
      description:
        "Report the state of the connection to the target socket: connected, ready, since " +
        "when, reconnect/attempt counts, bytes transferred, last activity, and last error.",
      inputSchema: {},
    },
    async () => {
      const stats = telnet.stats();
      return {
        content: [{ type: "text", text: JSON.stringify(stats, null, 2) }],
        structuredContent: stats as unknown as Record<string, unknown>,
      };
    },
  );

  return server;
}
