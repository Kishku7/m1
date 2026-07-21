package com.kishku7.m1;

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
 * M1-Legacy control channel: a localhost-only, newline-delimited text socket (trimmed front-door
 * port of modern M1's M1Server). Reads one command per line; replies with N lines terminated by a
 * "&lt;&lt;END" sentinel (RAW ON, machine clients) or without it (RAW OFF, humans). Protocol-compatible
 * with modern M1's external driver.
 *
 * MC-agnostic: all game-thread work goes through Platform.onMainThread + ScreenOps.dispatch, so this
 * file is copied verbatim into every legacy cell (no cog). Binds both loopback stacks (127.0.0.1 and
 * ::1). Commands run on the render thread; after a click/type the server settles briefly and appends
 * a fresh describe.
 */
public final class M1Server {

    public static final int PORT = Integer.getInteger("m1.port", 26000);
    private static final String END = "<<END\n";
    private static final long SETTLE_MS = 150;
    private static final int POLL_MS = 200;
    private static final int MAX_LINE = 1 << 20; // 1 MiB cap on an inbound line

    private static volatile boolean running = false;

    private M1Server() {}

    public static synchronized void start() {
        if (running) return;
        running = true;
        listen("127.0.0.1");
        listen("::1");
    }

    private static void listen(String host) {
        Thread t = new Thread(new Runnable() {
            public void run() { accept(host); }
        }, "m1-server-" + host);
        t.setDaemon(true);
        t.start();
    }

    private static void accept(String host) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName(host), PORT));
            log("listening on " + host + ":" + PORT);
            while (running) {
                final Socket c = ss.accept();
                Thread h = new Thread(new Runnable() {
                    public void run() { handle(c); }
                }, "m1-conn");
                h.setDaemon(true);
                h.start();
            }
        } catch (IOException e) {
            log("listener " + host + " unavailable: " + e);
        }
    }

    private static void handle(Socket c) {
        log("connection from " + c.getRemoteSocketAddress());
        boolean raw = true;
        try {
            Socket s = c;
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

            out.write("Connected to Machine One (M1-Legacy). Type HELP for commands.\n");
            out.write(END);
            out.flush();
            s.setSoTimeout(POLL_MS);

            while (true) {
                String line;
                try {
                    line = readLineCapped(in);
                } catch (SocketTimeoutException te) {
                    continue;
                }
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                String verb = line.split("\\s+", 2)[0].toLowerCase();
                if (verb.equals("quit") || verb.equals("exit")) break;
                if (verb.equals("raw")) {
                    String arg = line.length() > verb.length() ? line.substring(verb.length()).trim().toLowerCase() : "";
                    if (arg.equals("off")) { raw = false; writeResp(out, "Raw mode OFF.", raw); }
                    else if (arg.equals("on")) { raw = true; writeResp(out, "Raw mode ON.", raw); }
                    else writeResp(out, "Usage: RAW ON | RAW OFF", raw);
                    continue;
                }

                String resp = dispatchOnMainThread(line);
                out.write(resp);
                if (!resp.endsWith("\n")) out.write("\n");

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
            s.close();
        } catch (IOException e) {
            // client disconnected
        }
        log("connection closed");
    }

    private static void writeResp(BufferedWriter out, String resp, boolean raw) throws IOException {
        out.write(resp);
        if (!resp.endsWith("\n")) out.write("\n");
        if (raw) out.write(END);
        out.flush();
    }

    private static boolean isAction(String line) {
        String verb = line.split("\\s+", 2)[0].toLowerCase();
        return verb.equals("click") || verb.equals("type") || verb.equals("key");
    }

    private static String dispatchOnMainThread(String line) {
        final CompletableFuture<String> fut = new CompletableFuture<String>();
        final String cmd = line;
        Platform.onMainThread(new Runnable() {
            public void run() {
                try {
                    fut.complete(ScreenOps.dispatch(cmd));
                } catch (Throwable t) {
                    fut.complete("ERR " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        });
        try {
            return fut.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "ERR exec: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String readLineCapped(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean over = false;
        boolean started = false;
        while (true) {
            int ch;
            try {
                ch = in.read();
            } catch (SocketTimeoutException te) {
                if (!started) throw te;
                continue;
            }
            if (ch == -1) {
                if (!started) return null;
                break;
            }
            started = true;
            if (ch == '\n') break;
            if (!over && sb.length() < MAX_LINE) sb.append((char) ch);
            else over = true;
        }
        if (over) return "";
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
        return sb.toString();
    }

    public static void log(String m) {
        System.out.println("[M1] " + m);
    }
}
