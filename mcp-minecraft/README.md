# MCP-Minecraft

A small, generic **MCP (Model Context Protocol) server** that bridges a
newline-delimited TCP text socket to an MCP client such as Claude Desktop.

It was built to drive the **M1 Minecraft mod**, whose in-game control socket
listens on `localhost:26000` and speaks plain text (one command line in, one or
more reply lines out). But nothing here is Minecraft-specific: point it at any
program that exposes a line-based ("telnet-style") socket and you can drive that
program from an MCP client.

## What it does

- **Auto-connects when the target opens.** The bridge continuously tries to
  reach the target socket. The moment the port opens it connects; if the
  connection drops it reconnects automatically with a capped backoff. You can
  start the bridge before the target program is running.
- **Persistent MCP connection.** It serves MCP over Streamable HTTP with
  TCP keepalive and long idle timeouts, and exposes a `listen` tool designed to
  keep the client connection warm during quiet periods (see below).
- **Health endpoint.** `GET /health` reports whether the target socket is
  connected, plus uptime and traffic metrics.
- **No authentication.** The bridge is meant to run on a trusted network. See
  [Security](#security).

## How it fits together

```
  MCP client (e.g. Claude Desktop)
        |  Streamable HTTP  (persistent, keepalive)
        v
  MCP-Minecraft  ->  http://<host>:<PORT>/mcp   +   /health
        |  persistent TCP, auto-reconnect
        v
  target program (e.g. M1 mod)  ->  localhost:26000  (newline in / newline out)
```

## Requirements

- Node.js 18 or newer (works on macOS, Linux, and Windows).

## Install & build

```bash
npm install
npm run build
```

## Run

```bash
npm start
# or: node dist/index.js
```

On start it prints the MCP endpoint, the health URL, and the target address.

## Configuration

All settings are environment variables; every one is optional. Copy
`.env.example` to `.env` and edit, or export them in your shell / service unit.

| Variable            | Default     | Description |
|---------------------|-------------|-------------|
| `TARGET_HOST`       | `localhost` | Host of the socket to bridge. `localhost` resolves to IPv4 or IPv6, so an IPv6-only listener is reachable too. |
| `TARGET_PORT`       | `26000`     | Port of the socket to bridge. |
| `BIND_HOST`         | `0.0.0.0`   | Interface the HTTP server binds. `0.0.0.0` allows access from other machines (e.g. Claude Desktop on your LAN); use `127.0.0.1` to keep it local-only. |
| `PORT`              | `26001`     | Port for the HTTP server that serves `/mcp` and `/health`. |
| `RECONNECT_MIN_MS`  | `500`       | Initial reconnect delay to the target. |
| `RECONNECT_MAX_MS`  | `3000`      | Maximum reconnect delay (backoff cap). Lower = reconnects sooner after the target opens. |
| `QUIET_MS`          | `300`       | `send_command`: silence gap that marks a reply complete. |
| `SEND_TIMEOUT_MS`   | `5000`      | `send_command`: overall cap for collecting a reply. |
| `LISTEN_WINDOW_MS`  | `30000`     | `listen`: how long a call waits for output before returning a keepalive. |

## Connect an MCP client

Point your MCP client at the Streamable HTTP endpoint:

```
http://<host>:<PORT>/mcp
```

For example, on the same machine with defaults: `http://localhost:26001/mcp`.
In Claude Desktop, add it as a custom/remote MCP server using that URL.

## Tools

### `send_command`
Write one command line to the target and return the reply. The reply is
collected until the socket goes quiet (`QUIET_MS`) or `SEND_TIMEOUT_MS` elapses,
so multi-line replies come back whole. Returns an error if the target is not
currently connected.

- `command` (string, required) - the line to send; a trailing newline is added.
- `quiet_ms` (number, optional) - override the quiet-gap for this call.
- `timeout_ms` (number, optional) - override the overall cap for this call.

### `listen`
Block for up to `window_ms` (default `LISTEN_WINDOW_MS`, 30s) waiting for output
from the target, then return:

- the output, if any arrived;
- `NO-DATA-KEEPALIVE` if connected but idle for the whole window;
- `NO-CONNECTION-KEEPALIVE` if the target is not currently connected.

Call it repeatedly to stream asynchronous output and to hold the connection open
during quiet periods. Both keepalive replies are normal (non-error) results.

- `window_ms` (number, optional) - wait window for this call.

### `connection_status`
Return the state of the target connection as JSON: `connected`,
`connectedSince`, `reconnects`, `attempts`, `bytesIn`/`bytesOut`, `lastDataAt`,
`lastError`, and `queuedLines`.

## Health endpoint

`GET /health` returns JSON, for example:

```json
{
  "status": "ok",
  "service": "mcp-minecraft",
  "version": "0.1.0",
  "uptime_seconds": 360,
  "target": {
    "host": "localhost",
    "port": 26000,
    "connected": true,
    "connected_since": "2026-01-01T00:00:00.000Z",
    "reconnects": 1,
    "attempts": 1,
    "last_data_at": "2026-01-01T00:05:59.000Z",
    "last_error": null,
    "bytes_in": 1024,
    "bytes_out": 128,
    "queued_lines": 0
  },
  "mcp": { "active_sessions": 1 },
  "timestamp": "2026-01-01T00:06:00.000Z"
}
```

`target.connected` is the answer to "is there a live connection to port 26000?"

## Run as a service

### Linux (systemd)
An example unit is in [`systemd/mcp-minecraft.service`](systemd/mcp-minecraft.service).
Copy the built project to `/opt/mcp-minecraft`, then:

```bash
sudo cp systemd/mcp-minecraft.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mcp-minecraft
```

`Restart=on-failure` keeps the process alive; the bridge's own reconnect loop
keeps the target connection alive.

### macOS (launchd)
Create a LaunchAgent/LaunchDaemon plist whose `ProgramArguments` are
`/usr/bin/node /path/to/dist/index.js`, with `KeepAlive` set to `true`.

### Windows
Run at startup with a process supervisor such as
[NSSM](https://nssm.cc/) (`nssm install MCP-Minecraft "C:\Program Files\nodejs\node.exe" "C:\path\to\dist\index.js"`)
or a Task Scheduler task set to run `node dist\index.js` at logon and restart on
failure.

## Security

There is **no authentication**. Anyone who can reach `PORT` can drive the target
program. Run it only on a trusted network, or bind it to `127.0.0.1`
(`BIND_HOST=127.0.0.1`) and reach it through your own tunnel/proxy. Do not expose
it directly to the public internet without an authenticating reverse proxy in
front.

## License

MIT - see [LICENSE](LICENSE).
