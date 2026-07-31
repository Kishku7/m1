package com.kishku7.m1;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat relay + master tracking. M1 forwards EVERY player's chat line to the AI as a pushed
 * "[chat] <name>: <text>" report (drained by {@link M1Server}, exactly like the other async
 * reports). The MOD does not decide who becomes master -- it only STORES whatever name the AI
 * tells it via the "master &lt;name&gt;" socket verb (see ScreenOps.dispatch), and exposes that
 * stored name to the gameplay systems that need an identity to act on (follow/defend/threat-watch).
 * Recognizing a handshake in natural chat is the AI's job, not a hardcoded phrase match.
 *
 * (Design change 2026-07-31, Master: the old approach hardcoded an EXACT phrase ("Who is your
 * daddy", case- and punctuation-sensitive) and gated ALL chat relay behind it -- both too brittle
 * for natural typing and, on pre-26 Fabric cells, never even wired to a chat event at all, so it
 * could never register regardless of phrasing. Now the mod is a pure relay + a settable slot.)
 *
 * Once a master IS set, relay narrows back to that master's chat only (the original safety
 * posture: on a shared/multiplayer server, other players' chat should not silently reach the AI
 * as if it were the master's). Call "master clear" (or the AI can) to reopen relay to everyone,
 * e.g. to hand off to a different master.
 */
public final class ChatWatch {

    private static volatile String master = null;
    private static final List<String> reports = new ArrayList<>();
    private static final int MAX_REPORTS = 128;

    private ChatWatch() {}

    public static synchronized String master() { return master; }

    /** Set by the AI via the "master <name>" socket verb -- never by the mod itself. */
    public static synchronized void setMaster(String name) {
        String n = (name == null) ? "" : name.trim();
        if (n.isEmpty()) {
            clearMaster();
            return;
        }
        master = n;
        M1Server.log("ChatWatch MASTER set to " + master);
        pushReport("[master] " + master + " is now master. Chat relay narrowed to " + master + " only.");
    }

    /** Forget the current master ("master clear") -- relay reopens to every player's chat. */
    public static synchronized void clearMaster() {
        boolean had = master != null;
        master = null;
        if (had) {
            M1Server.log("ChatWatch MASTER cleared");
            pushReport("[master] cleared -- chat relay open to all players again.");
        }
    }

    /** Called by the loader entrypoint for each incoming chat message; {@code name} may be null. */
    public static synchronized void onChat(String name, String text) {
        if (text == null) return;
        String msg = text.trim();
        if (msg.isEmpty()) return;
        String tag = (name != null && !name.isEmpty()) ? name : "?";
        if (master != null && !tag.equalsIgnoreCase(master)) {
            return; // narrowed to master only; everyone else is ignored once a master is set
        }
        pushReport("[chat] " + tag + ": " + msg);
    }

    private static void pushReport(String r) {
        if (reports.size() >= MAX_REPORTS) {
            reports.remove(0);
        }
        reports.add(r);
    }

    /** Drain pending chat/master lines for the socket. Mirrors {@link M1SrvNet#drainReports}. */
    public static synchronized String drainReports() {
        if (reports.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String r : reports) b.append(r).append("\n");
        reports.clear();
        if (b.length() > 0 && b.charAt(b.length() - 1) == '\n') b.setLength(b.length() - 1);
        return b.toString();
    }
}