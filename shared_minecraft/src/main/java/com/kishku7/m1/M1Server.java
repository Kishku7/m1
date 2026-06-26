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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * M1 control channel: a localhost-only, newline-delimited text socket.
 * No MCP, no WebSocket. Reads one command per line; replies with N lines
 * of text terminated by a "&lt;&lt;END" sentinel line.
 *
 * Commands run on the client main thread (Minecraft is not thread-safe) via
 * Minecraft.execute(), bridged back with a CompletableFuture. After an action
 * command (click/type) the server waits a brief settle and auto-appends a fresh
 * describe of the resulting screen, so every action reports the new situation.
 */
public final class M1Server {

    public static final int PORT = Integer.getInteger("m1.port", 26000);
    private static final String END = "<<END\n";
    private static final long SETTLE_MS = 150;

    private static volatile boolean running = false;

    private M1Server() {}

    public static synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(M1Server::run, "m1-server");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT));
            log("listening on 127.0.0.1:" + PORT);
            while (running) {
                Socket c = ss.accept();
                Thread h = new Thread(() -> handle(c), "m1-conn");
                h.setDaemon(true);
                h.start();
            }
        } catch (IOException e) {
            log("server error: " + e);
        }
    }

    private static void handle(Socket c) {
        log("connection from " + c.getRemoteSocketAddress());
        try (Socket s = c;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            out.write("M1 ready. Type 'help'.\n");
            out.write(END);
            out.flush();

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) break;

                String resp = dispatchOnMainThread(line);
                String ups = PickupUpgrade.drainReports();
                if (!ups.isEmpty()) { out.write(ups); out.write("\n"); }
                out.write(resp);
                if (!resp.endsWith("\n")) out.write("\n");

                // Auto-confirm + auto-describe: after an action, let the screen settle
                // (button presses can trigger a fade/transition) then report the new state.
                if (isAction(line)) {
                    try { Thread.sleep(SETTLE_MS); } catch (InterruptedException ignored) {}
                    String now = dispatchOnMainThread("describe");
                    out.write("--- now ---\n");
                    out.write(now);
                    if (!now.endsWith("\n")) out.write("\n");
                }

                out.write(END);
                out.flush();
            }
        } catch (IOException e) {
            // client disconnected
        }
        log("connection closed");
    }

    private static boolean isAction(String line) {
        String verb = line.split("\\s+", 2)[0].toLowerCase();
        return verb.equals("click") || verb.equals("type");
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

