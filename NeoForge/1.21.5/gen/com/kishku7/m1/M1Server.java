package com.kishku7.m1;

import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * M1 control channel: a localhost-only, newline-delimited text socket.
 * No MCP, no WebSocket. Reads one command per line; replies with N lines
 * of text terminated by a "&lt;&lt;END" sentinel line.
 *
 * Binds BOTH loopback stacks (127.0.0.1 and ::1) on PORT, so `telnet localhost 26000` connects
 * regardless of whether the client resolves localhost to IPv4 or IPv6. Both binds are loopback
 * addresses -- never externally reachable. If one stack is unavailable it is logged and skipped.
 *
 * Dual-audience connect protocol (friendly to a telnet human AND an AI):
 *   greeting -&gt; "Connected to Machine One (M1). Type START to begin, or HELP for commands."
 *   START    -&gt; the AI's brain index-file path + the human's HELP/RAW tip (see AiBrain.startBrief()).
 *   HELP     -&gt; the command list (+ a tip line). RAW OFF hides the &lt;&lt;END marker for humans.
 * START is optional discovery, NOT a gate -- commands work immediately for clients that know them.
 *
 * Commands run on the client main thread (Minecraft is not thread-safe) via Minecraft.execute(),
 * bridged back with a CompletableFuture. After an action command (click/type) the server waits a
 * brief settle and auto-appends a fresh describe of the resulting screen.
 */
public final class M1Server {

    public static final int PORT = Integer.getInteger("m1.port", 26000);
    private static final String END = "<<END\n";
    private static final long SETTLE_MS = 150;
    private static final int POLL_MS = 200; // idle read timeout: how often to push async mod events

    private static volatile boolean running = false;

    private M1Server() {}

    public static synchronized void start() {
        if (running) return;
        running = true;
        AiBrain.install();   // extract/refresh the shipped AI_Brain docs into the user's config
        listen("127.0.0.1"); // IPv4 loopback
        listen("::1");       // IPv6 loopback -- both so `telnet localhost 26000` works either way
    }

    private static void listen(String host) {
        Thread t = new Thread(() -> accept(host), "m1-server-" + host);
        t.setDaemon(true);
        t.start();
    }

    private static void accept(String host) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName(host), PORT));
            log("listening on " + host + ":" + PORT);
            while (running) {
                Socket c = ss.accept();
                Thread h = new Thread(() -> handle(c), "m1-conn");
                h.setDaemon(true);
                h.start();
            }
        } catch (IOException e) {
            log("listener " + host + " unavailable: " + e);
        }
    }

    private static void handle(Socket c) {
        log("connection from " + c.getRemoteSocketAddress());
        boolean raw = true;   // per-connection: emit the <<END sentinel (machine clients). RAW OFF for humans.
        try (Socket s = c;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            out.write("Connected to Machine One (M1). Type START to begin, or HELP for commands.\n");
            if (!AiBrain.warning.isEmpty()) out.write("(!) brain warning -- type START to view it.\n");
            out.write(END);
            out.flush();

            s.setSoTimeout(POLL_MS); // wake periodically to push async mod events with no command pending

            while (true) {
                String line;
                try {
                    line = in.readLine();
                } catch (SocketTimeoutException te) {
                    flushAsync(out); // no command this window -- push any pending mod events
                    continue;
                }
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                String verb = line.split("\\s+", 2)[0].toLowerCase();
                if (verb.equals("quit") || verb.equals("exit")) break;

                String resp;
                if (verb.equals("start")) {
                    resp = AiBrain.startBrief();
                } else if (verb.equals("raw")) {
                    String arg = line.length() > verb.length() ? line.substring(verb.length()).trim().toLowerCase() : "";
                    if (arg.equals("off")) {
                        raw = false;
                        resp = "Raw mode OFF -- the <<END end-of-reply marker is now hidden (friendlier for humans). Type RAW ON to restore it.";
                    } else if (arg.equals("on")) {
                        raw = true;
                        resp = "Raw mode ON -- replies end with the <<END marker.";
                    } else {
                        resp = "Usage: RAW ON | RAW OFF. OFF hides the <<END end-of-reply marker -- use it if you are a human on a terminal.";
                    }
                } else {
                    resp = dispatch(line);
                    if (verb.equals("help")) {
                        resp = resp + "\nTip: humans -> type RAW OFF for cleaner output.";
                    }
                }

                // Chat/master lines FIRST -- orders must never scroll under nav telemetry (702d).
                String chat = ChatWatch.drainReports();
                if (!chat.isEmpty()) { out.write(chat); out.write("\n"); }
                String bv = VaultNet.drainReports();
                if (!bv.isEmpty()) { out.write(bv); out.write("\n"); }
                String m1s = M1SrvNet.drainReports();
                if (!m1s.isEmpty()) { out.write(m1s); out.write("\n"); }
                String alerts = DamageWatch.drainReports();
                if (!alerts.isEmpty()) { out.write(alerts); out.write("\n"); }
                String ups = PickupUpgrade.drainReports();
                if (!ups.isEmpty()) { out.write(ups); out.write("\n"); }
                String agentReports = AgentRuntime.drainReports();
                if (!agentReports.isEmpty()) { out.write(agentReports); out.write("\n"); }
                out.write(resp);
                if (!resp.endsWith("\n")) out.write("\n");

                // Auto-confirm + auto-describe: after an action, let the screen settle then report state.
                if (isAction(line)) {
                    try { Thread.sleep(SETTLE_MS); } catch (InterruptedException ignored) {}
                    String now = dispatchOnMainThread("describe");
                    out.write("--- now ---\n");
                    out.write(now);
                    if (!now.endsWith("\n")) out.write("\n");
                }

                if (raw) out.write(END);
                out.flush();
            }
        } catch (IOException e) {
            // client disconnected
        }
        log("connection closed");
    }

    /** Push any pending async mod events (alerts / agent / auto-upgrade) between commands so a
     *  polling client (listen) sees them promptly. Called on the idle read timeout. */
    private static void flushAsync(BufferedWriter out) throws IOException {
        boolean any = false;
        String ch = ChatWatch.drainReports(); // chat first (702d: orders were buried under telemetry)
        if (!ch.isEmpty()) { out.write(ch); out.write("\n"); any = true; }
        String bvp = VaultNet.drainReports();
        if (!bvp.isEmpty()) { out.write(bvp); out.write("\n"); any = true; }
        String m1sp = M1SrvNet.drainReports();
        if (!m1sp.isEmpty()) { out.write(m1sp); out.write("\n"); any = true; }
        String al = DamageWatch.drainReports();
        if (!al.isEmpty()) { out.write(al); out.write("\n"); any = true; }
        String ups = PickupUpgrade.drainReports();
        if (!ups.isEmpty()) { out.write(ups); out.write("\n"); any = true; }
        String ag = AgentRuntime.drainReports();
        if (!ag.isEmpty()) { out.write(ag); out.write("\n"); any = true; }
        if (any) out.flush();
    }

    private static boolean isAction(String line) {
        String verb = line.split("\\s+", 2)[0].toLowerCase();
        return verb.equals("click") || verb.equals("type");
    }

    // Route a command. Most verbs run on the render-main thread (Minecraft is not thread-safe).
    // The screenshot verb is the exception: it MUST run on this (connection) thread and only
    // marshal its grab() onto the main thread internally -- blocking the main thread waiting on
    // the async GPU readback + PNG encode would deadlock the very flush it depends on.
    private static String dispatch(String line) {
        String verb = line.split("\\s+", 2)[0].toLowerCase();
        if (verb.equals("screenshot") || verb.equals("shot")) {
            String rest = line.length() > verb.length() ? line.substring(verb.length()).trim() : "";
            try {
                return ScreenOps.screenshot(Minecraft.getInstance(), rest);
            } catch (Throwable t) {
                return "ERR screenshot: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
        return dispatchOnMainThread(line);
    }

    private static String dispatchOnMainThread(String line) {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<String> fut = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                fut.complete(ScreenOps.dispatch(mc, line));
            } catch (Throwable t) {
                fut.complete("ERR " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
        try {
            return fut.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "ERR exec: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public static void log(String m) {
        System.out.println("[M1] " + m);
    }
}
