package com.kishku7.m1;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bridge to Bank Vault's 1.4.0+ automation surface. Two transports, tried in order:
 *
 *   1. In-JVM (preferred, SP only): reflect com.kishku7.bankvault.api.VaultApi and invoke it on
 *      the integrated-server thread (the api requires the server thread). Zero chat traffic,
 *      zero parsing -- the api returns the single "BV|op|..." ASCII line directly.
 *   2. Chat round-trip (MP, or whenever the integrated server is absent): send "/bank api ..."
 *      as the player and capture the "BV|" system chat line. The capture is wired from the
 *      loader entrypoints via {@link #onSystemLine} (26-line entrypoints only for now; pre-26
 *      cells rely on the in-JVM path in SP).
 *
 * Returns null when neither transport produced a BV| line (BV &lt; 1.4.0, BV absent, or timeout)
 * -- callers fall back to the legacy GUI / plain-command paths in {@link VaultOps}.
 */
public final class VaultNet {

    private static final String API_CLASS = "com.kishku7.bankvault.api.VaultApi";
    private static final long JVM_TIMEOUT_MS = 2000;
    private static final long CHAT_TIMEOUT_MS = 3000;

    /** Single pending chat waiter (socket commands are serial). */
    private static String waitOp = null;
    private static CompletableFuture<String> waitFut = null;

    private VaultNet() {
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
     * VaultApi parameters AFTER the leading ServerPlayer. Returns the BV| result line, or null
     * if no transport could produce one.
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
        CompletableFuture<String> fut = new CompletableFuture<>();
        synchronized (VaultNet.class) {
            waitOp = op;
            waitFut = fut;
        }
        try {
            mc.getConnection().sendCommand(
                    "bank api " + op + (chatTail == null || chatTail.isEmpty() ? "" : " " + chatTail));
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
     * feedback / system messages, not player chat). Non-BV lines are ignored cheaply.
     */
    public static void onSystemLine(String text) {
        if (text == null) {
            return;
        }
        String line = text.trim();
        if (!line.startsWith("BV|")) {
            return;
        }
        CompletableFuture<String> fut;
        String op;
        synchronized (VaultNet.class) {
            fut = waitFut;
            op = waitOp;
        }
        if (fut != null && op != null && line.startsWith("BV|" + op + "|")) {
            fut.complete(line);
        }
    }
}
