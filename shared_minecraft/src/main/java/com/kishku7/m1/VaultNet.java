package com.kishku7.m1;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bridge to Bank Vault's 1.4.0+ automation surface. Two transports, tried in order:
 *
 *   1. In-JVM (preferred, SP only): reflect com.kishku7.bankvault.api.VaultApi and invoke it on
 *      the integrated-server thread (the api requires the server thread). Zero chat traffic,
 *      zero parsing -- the api returns the single "BV|op|..." ASCII line directly. Blocking the
 *      render thread on the SERVER thread is safe (different threads).
 *
 *   2. Chat round-trip (MP): send "/bank api ..." as the player and capture the "BV|" SYSTEM
 *      chat line via the loader GAME-message event. CRITICAL THREADING FACT (learned live on the
 *      Family server, 2026-07-03): socket commands dispatch ON THE RENDER THREAD
 *      (M1Server.dispatchOnMainThread) and the GAME event ALSO fires on the render thread -- so
 *      a blocking wait here can NEVER be satisfied (self-deadlock, guaranteed timeout). The chat
 *      transport is therefore ASYNC in that case: the command is sent, the verb returns "OK
 *      sent ...", and the BV| reply is pushed to the socket as a "[bv] ..." report line when it
 *      arrives (drained by M1Server like ChatWatch/agent reports). The blocking waiter remains
 *      for genuinely off-render-thread callers.
 *
 * callJvm returning null (BV < 1.4.0, BV absent) lets callers fall back to the legacy GUI /
 * plain-command paths in {@link VaultOps}. If a chat-mode send produces no [bv] line, the
 * server's BV predates 1.4.0.
 */
public final class VaultNet {

    private static final String API_CLASS = "com.kishku7.bankvault.api.VaultApi";
    private static final long JVM_TIMEOUT_MS = 2000;
    private static final long CHAT_TIMEOUT_MS = 3000;
    private static final int MAX_REPORTS = 32;

    /** Single pending chat waiter (only used by off-render-thread callers). */
    private static String waitOp = null;
    private static CompletableFuture<String> waitFut = null;

    /** Async BV| lines awaiting the socket flush ("[bv] ..." reports). */
    private static final List<String> reports = new ArrayList<>();

    /** Diagnostics: every onSystemLine delivery + the BV| subset (socket-visible via vault status). */
    private static volatile long sysSeen = 0;
    private static volatile long bvSeen = 0;
    private static volatile String lastSys = "";

    private VaultNet() {
    }

    /** One-line delivery diagnostics for vault status. */
    static String debug() {
        return "sys=" + sysSeen + " bv=" + bvSeen + (lastSys.isEmpty() ? "" : " last='" + lastSys + "'");
    }

    /** True when the BV api class is loadable in this JVM (BV 1.4.0+ present). */
    static boolean inJvm() {
        try {
            Class.forName(API_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Run a BV api op. {@code chatTail} is the argument tail for the "/bank api &lt;op&gt; ..."
     * chat form (BV arg order: count FIRST for withdraw/deposit); {@code jvmArgs} are the
     * VaultApi parameters AFTER the leading ServerPlayer. Returns the BV| result line (in-JVM),
     * an "OK sent ..." async note (MP chat mode on the render thread), or null when no
     * transport could run (callers use their legacy fallback).
     */
    static String call(Minecraft mc, String op, String chatTail, Object... jvmArgs) {
        String r = callJvm(mc, op, jvmArgs);
        if (r != null) {
            return r;
        }
        return callChat(mc, op, chatTail);
    }

    // ---------- transport 1: in-JVM reflection on the integrated-server thread ----------

    private static String callJvm(Minecraft mc, String op, Object... jvmArgs) {
        net.minecraft.client.server.IntegratedServer srv = mc.getSingleplayerServer();
        if (srv == null || mc.player == null || !inJvm()) {
            return null;
        }
        java.util.UUID uuid = mc.player.getUUID();
        CompletableFuture<String> fut = new CompletableFuture<>();
        srv.execute(() -> {
            try {
                net.minecraft.server.level.ServerPlayer sp = srv.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    fut.complete(null);
                    return;
                }
                Class<?> api = Class.forName(API_CLASS);
                Method m = null;
                for (Method cand : api.getMethods()) {
                    if (cand.getName().equals(op) && cand.getParameterCount() == jvmArgs.length + 1) {
                        m = cand;
                        break;
                    }
                }
                if (m == null) {
                    fut.complete(null);
                    return;
                }
                Object[] a = new Object[jvmArgs.length + 1];
                a[0] = sp;
                System.arraycopy(jvmArgs, 0, a, 1, jvmArgs.length);
                fut.complete(String.valueOf(m.invoke(null, a)));
            } catch (Throwable t) {
                M1Server.log("VaultNet jvm " + op + ": " + t);
                fut.complete(null);
            }
        });
        try {
            return fut.get(JVM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- transport 2: /bank api chat round-trip ----------

    private static String callChat(Minecraft mc, String op, String chatTail) {
        if (mc.getConnection() == null) {
            return null;
        }
        String cmd = "bank api " + op + (chatTail == null || chatTail.isEmpty() ? "" : " " + chatTail);
        if (mc.isSameThread()) {
            // Render thread (the normal socket-dispatch case): NEVER block -- the GAME event
            // that carries the reply fires on THIS thread. Fire and report async.
            mc.getConnection().sendCommand(cmd);
            return "OK sent /" + cmd + " -- the BV| reply arrives as a [bv] line "
                    + "(no [bv] line = the server's Bank Vault predates 1.4.0)";
        }
        CompletableFuture<String> fut = new CompletableFuture<>();
        synchronized (VaultNet.class) {
            waitOp = op;
            waitFut = fut;
        }
        try {
            mc.getConnection().sendCommand(cmd);
            return fut.get(CHAT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return null;
        } finally {
            synchronized (VaultNet.class) {
                waitOp = null;
                waitFut = null;
            }
        }
    }

    /**
     * Feed system chat lines here from the loader entrypoint (BV's api replies are command
     * feedback / system messages, not player chat). Non-BV lines are ignored cheaply. BV lines
     * complete a pending off-thread waiter if one matches, otherwise they are queued as "[bv]"
     * reports for the socket flush.
     */
    public static void onSystemLine(String text) {
        if (text == null) {
            return;
        }
        String line = text.trim();
        sysSeen++;
        if (!line.isEmpty()) {
            lastSys = line.length() > 70 ? line.substring(0, 70) : line;
        }
        if (!line.startsWith("BV|")) {
            return;
        }
        bvSeen++;
        CompletableFuture<String> fut;
        String op;
        synchronized (VaultNet.class) {
            fut = waitFut;
            op = waitOp;
        }
        if (fut != null && op != null && line.startsWith("BV|" + op + "|")) {
            fut.complete(line);
            return;
        }
        synchronized (reports) {
            if (reports.size() < MAX_REPORTS) {
                reports.add("[bv] " + line);
            }
        }
    }

    /** Drain pending [bv] lines for the socket. Mirrors {@link ChatWatch#drainReports}. */
    public static String drainReports() {
        synchronized (reports) {
            if (reports.isEmpty()) {
                return "";
            }
            StringBuilder b = new StringBuilder();
            for (String r : reports) {
                b.append(r).append("\n");
            }
            reports.clear();
            b.setLength(b.length() - 1);
            return b.toString();
        }
    }
}
