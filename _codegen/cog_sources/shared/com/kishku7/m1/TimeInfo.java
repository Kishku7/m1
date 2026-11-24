package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * In-game time for every socket reply, so a driving AI always knows the time of day without asking
 * (bed routines, dusk/dawn planning). Reads the overworld day clock through {@link M1Compat#dayClock}.
 *
 * <p>Two outputs, both plain text, both empty when no world is loaded:
 * <ul>
 *   <li>{@link #line()} -- one {@code [time]} line appended to a reply:
 *       {@code [time] day 725 tick 7956 (13:12) beds in 4586t (3m49s)} or
 *       {@code [time] day 725 tick 14000 (19:48) beds OK now (night, 9459t left)}.</li>
 *   <li>{@link #drainEvents()} -- a pushed {@code [time]} event when beds become usable or stop being
 *       usable, so an idle listen loop hears about dusk and dawn.</li>
 * </ul>
 * Tick 0 is 06:00. Beds are usable when it is not bright outside, or during a thunderstorm -- the same
 * test {@link SleepAction} uses. The clock is the OVERWORLD clock even when the player is in another
 * dimension; the line says so. Reads happen on the connection thread; they are plain field reads of
 * values the client already holds, and every read is guarded so a failure just yields no line.
 */
public final class TimeInfo {
    private static final long DAY = 24000L;
    /** First tick at which vanilla lets a player sleep in clear weather. */
    private static final long NIGHT_START = 12542L;
    /** First tick at which it is no longer night (beds stop working). */
    private static final long NIGHT_END = 23460L;

    private static Boolean lastSleepOk = null; // null until a world has been observed
    private static final Object LOCK = new Object();

    private TimeInfo() {}

    private static Level level() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : mc.level;
    }

    private static boolean sleepOk(Level lvl) {
        return !M1Compat.isBrightOutside(lvl) || lvl.isThundering();
    }

    /** The {@code [time]} line for the current moment, or "" when there is no world or it cannot be read. */
    public static String line() {
        try {
            Level lvl = level();
            if (lvl == null) return "";
            long clock = M1Compat.dayClock(lvl);
            long tod = Math.floorMod(clock, DAY);
            long hour = (tod / 1000L + 6L) % 24L;
            long minute = (tod % 1000L) * 60L / 1000L;
            StringBuilder sb = new StringBuilder(96);
            sb.append(String.format(Locale.ROOT, "[time] day %d tick %d (%02d:%02d) ", Math.floorDiv(clock, DAY), tod, hour, minute));
            boolean ok = sleepOk(lvl);
            if (ok) {
                if (lvl.isThundering() && M1Compat.isBrightOutside(lvl)) {
                    sb.append("beds OK now (thunderstorm)");
                } else {
                    long left = Math.floorMod(NIGHT_END - tod, DAY);
                    sb.append("beds OK now (night, ").append(left).append("t left)");
                }
            } else {
                long until = Math.floorMod(NIGHT_START - tod, DAY);
                sb.append("beds in ").append(until).append("t (").append(approx(until)).append(')');
            }
            String dim = M1Compat.keyId(lvl.dimension());
            if (!"minecraft:overworld".equals(dim)) sb.append(" [in ").append(dim).append("; overworld clock]");
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** A pushed event when beds just became usable or just stopped being usable; "" otherwise. */
    public static String drainEvents() {
        try {
            Level lvl = level();
            synchronized (LOCK) {
                if (lvl == null) {
                    lastSleepOk = null;
                    return "";
                }
                boolean ok = sleepOk(lvl);
                Boolean prev = lastSleepOk;
                lastSleepOk = ok;
                if (prev == null || prev == ok) return "";
                String l = line();
                return ok ? "[time] beds are usable now. " + l.replace("[time] ", "")
                          : "[time] beds are no longer usable. " + l.replace("[time] ", "");
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static String approx(long ticks) {
        long secs = ticks / 20L;
        return (secs / 60L) + "m" + String.format(Locale.ROOT, "%02d", secs % 60L) + "s";
    }
}
