package com.kishku7.m1;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat master + relay. M1 watches incoming in-game chat (wired via a loader chat-receive event that
 * calls {@link #onChat}). Until a master is set, it waits for a player to say EXACTLY the handshake
 * phrase; that player becomes the master. Thereafter ONLY the master's chat is forwarded to the AI
 * as pushed "[chat] <master>: <text>" lines (flushed by {@link M1Server} like the other async
 * reports). Everyone else's chat is ignored.
 *
 * The AI -- not the mod -- interprets the master's words into M1 commands, keeps them Minecraft-only,
 * and acks with the `say` command. The mod only captures, gates to the master, and forwards.
 */
public final class ChatWatch {

    /** Exact phrase (trimmed) a player must say to become master. */
    public static final String HANDSHAKE = "Who is your daddy";

    private static volatile String master = null;
    private static final List<String> reports = new ArrayList<>();

    private ChatWatch() {}

    public static synchronized String master() { return master; }

    /** Forget the current master (future 'release master' command can call this). */
    public static synchronized void clearMaster() { master = null; }

    /** Called by the loader entrypoint for each incoming chat message; {@code name} may be null. */
    public static synchronized void onChat(String name, String text) {
        if (text == null) return;
        String msg = text.trim();
        if (msg.isEmpty()) return;
        if (master == null) {
            if (msg.equals(HANDSHAKE) && name != null && !name.isEmpty()) {
                master = name;
                M1Server.log("ChatWatch MASTER set to " + name);
                reports.add("[master] " + name + " is now master. Obey Minecraft commands from "
                        + name + " only; reply with `say Ok`.");
            }
            return; // no master yet: ignore all other chat
        }
        if (name != null && name.equalsIgnoreCase(master)) {
            reports.add("[chat] " + master + ": " + msg);
        }
        // non-master chat is ignored
    }

    /** Drain pending chat/master lines for the socket. Mirrors {@link PickupUpgrade#drainReports}. */
    public static synchronized String drainReports() {
        if (reports.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String r : reports) b.append(r).append("\n");
        reports.clear();
        if (b.length() > 0 && b.charAt(b.length() - 1) == '\n') b.setLength(b.length() - 1);
        return b.toString();
    }
}
