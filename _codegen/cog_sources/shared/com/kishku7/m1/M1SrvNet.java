package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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
 * Fully OPTIONAL, zero coupling: with no M1-Server installed, "/m1srv" is an unknown command server-side.
 * BEFORE sending anything, {@link #serverSupportsM1Srv} checks the server's own command tree (delivered
 * via ClientboundCommandsPacket at login, well before this ever ticks) for an "m1srv" root literal, so no
 * query is ever actually sent to a server that doesn't have it. Found 2026-07-31: sending regardless and
 * relying on "no M1S| reply arrives" as the failure signal is not actually silent -- Minecraft itself
 * echoes "Unknown or incomplete command" to the player's VISIBLE system chat for every rejected command,
 * so the old behavior produced five visible error lines on every join to an M1-less server. The capability
 * check removes that: neither mod depends on the other, and now neither mod is visibly affected either.
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

    /**
     * Loader-agnostic facade: every loader entrypoint feeds its client system-chat message HERE as a
     * Component, and the version-drifting bit (Component.getString(), which each loader remaps to its
     * own runtime name at build) lives in this ONE shared method -- so no entrypoint duplicates it and
     * SRG/intermediary-runtime cells (Forge 1.20.1) work the same as mojmap ones. Null-safe.
     */
    public static void onChatMessage(Component msg) {
        if (msg != null) {
            onSystemLine(msg.getString());
        }
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

    /**
     * True if the server's command tree (populated from ClientboundCommandsPacket at login, always
     * received before the player spawns) declares an "m1srv" root literal. Direct-compiled (not
     * reflection) so it works on every runtime -- mojmap, intermediary, and SRG cells alike -- the
     * same way the rest of this file's direct Minecraft/Component calls already do; `var` sidesteps
     * naming the suggestion-provider generic, which differs by era (SharedSuggestionProvider pre-26,
     * ClientSuggestionProvider from 26 on) but doesn't affect this lookup.
     */
    private static boolean serverSupportsM1Srv(Minecraft mc) {
        try {
            var dispatcher = mc.getConnection().getCommands();
            return dispatcher != null && dispatcher.getRoot().getChild("m1srv") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Call from the client tick loop. On the not-connected -> connected edge, auto-grab the essentials
     *  -- but only if the server actually declares the command, so a server without M1/M1-Server never
     *  sees a doomed command and the player never sees the resulting "Unknown command" chat spam. */
    public static void tick(Minecraft mc) {
        boolean connected = mc.getConnection() != null && mc.player != null;
        if (connected && !wasConnected) {
            announced = false;
            if (serverSupportsM1Srv(mc)) {
                for (String q : ESSENTIALS) {
                    mc.getConnection().sendCommand("m1srv q " + q);
                }
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
        if (!serverSupportsM1Srv(mc)) {
            return "m1srv: not supported on this server (no m1srv command registered) -- M1-Server/M1 is not installed server-side here";
        }
        String t = tail.trim();
        mc.getConnection().sendCommand("m1srv q " + t);
        return "OK sent: m1srv q " + t + " -- reply arrives as an [m1s] line";
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