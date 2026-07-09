package com.kishku7.m1;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional bridge to the M1-Server companion mod. M1-Server (server-side) answers "/m1srv q &lt;query&gt;"
 * with a system-chat line "M1S|&lt;seq&gt;|&lt;query&gt;|&lt;payload&gt;". This captures those lines and
 * surfaces them to the AI as async "[m1s] ..." report lines (drained by {@link M1Server}, exactly like
 * {@link VaultNet}). On the connect edge it AUTO-issues the "essential" queries, so the AI learns the
 * static world facts WITHOUT asking; the first M1S| reply proves M1-Server is present and emits a one-time
 * availability note so the AI knows extra info is on offer.
 *
 * Fully OPTIONAL, zero coupling: with no M1-Server installed, "/m1srv" is an unknown command server-side,
 * no M1S| line ever arrives, and M1 is completely unaffected. Neither mod depends on the other.
 *
 * Threading: system-chat events + socket verbs both fire on the render thread, so nothing here blocks
 * (mirrors VaultNet's async-report path).
 */
public final class M1SrvNet {

    private static final int MAX_REPORTS = 64;
    /** Static world facts auto-grabbed once per connect (dynamic ones stay on-demand). */
    private static final String[] ESSENTIALS = {"seed", "spawn", "worldborder", "difficulty", "gamerules"};

    private static final List<String> reports = new ArrayList<>();
    private static volatile boolean announced = false;
    private static volatile boolean wasConnected = false;

    private M1SrvNet() {
    }

    /** Feed system-chat lines here from the loader entrypoint (same feed as VaultNet). */
    public static void onSystemLine(String text) {
        if (text == null) {
            return;
        }
        String line = text.trim();
        if (!line.startsWith("M1S|")) {
            return;
        }
        // M1S|<seq>|<query>|<payload>
        String query = "?";
        String payload = line;
        String[] parts = line.split("\\|", 4);
        if (parts.length >= 4) {
            query = parts[2];
            payload = parts[3];
        }
        synchronized (reports) {
            if (!announced) {
                announced = true;
                pushReport("[m1s] M1-Server present on this world -- extra info available on demand via the "
                        + "'m1srv <query>' verb: seed, spawn, worldborder, difficulty, time, weather, gamerules, "
                        + "players, serverinfo, playerdir, lookingat, recipe <id>, advancement <id>, "
                        + "looked-at block/entity NBT, locate structure|biome <id>.");
            }
            pushReport("[m1s] " + query + " = " + payload);
        }
    }

    private static void pushReport(String r) {
        if (reports.size() < MAX_REPORTS) {
            reports.add(r);
        }
    }

    /** Call from the client tick loop. On the not-connected -> connected edge, auto-grab the essentials. */
    public static void tick(Minecraft mc) {
        boolean connected = mc.getConnection() != null && mc.player != null;
        if (connected && !wasConnected) {
            announced = false;
            for (String q : ESSENTIALS) {
                mc.getConnection().sendCommand("m1srv q " + q);
            }
        }
        wasConnected = connected;
    }

    /** On-demand query from the socket "m1srv &lt;tail&gt;" verb. Render-thread only; never blocks. */
    public static String requestOne(Minecraft mc, String tail) {
        if (mc.getConnection() == null || mc.player == null) {
            return "m1srv: not in world";
        }
        if (tail == null || tail.isBlank()) {
            return "m1srv: usage -- m1srv <query> (e.g. 'm1srv seed', 'm1srv locate biome minecraft:desert')";
        }
        String t = tail.trim();
        mc.getConnection().sendCommand("m1srv q " + t);
        return "OK sent: m1srv q " + t + " -- reply arrives as an [m1s] line (no line = M1-Server not installed here)";
    }

    /** Drain pending [m1s] lines for the socket. Mirrors {@link VaultNet#drainReports}. */
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
