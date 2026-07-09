package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Async under-attack / damage watcher. Each client tick it compares the local player's health to
 * last tick; on any decrease it queues an "[alert]" line reporting the damage, the new health, and
 * the nearest hostile mob (best-effort likely source). Lines are flushed onto the next socket reply
 * by {@link M1Server}, exactly like {@link PickupUpgrade}/{@link AgentRuntime} reports, so a driver
 * learns it is being hit without having to keep polling {@code where}.
 *
 * <p>Health-delta only -- no mixin, no hurtTime field access -- so it is fully version-stable across
 * every supported MC version. {@code lastHealth} resets out of world so a respawn does not fire a
 * spurious alert.
 */
public final class DamageWatch {

    private static final double SOURCE_RADIUS = 10.0;

    private static float lastHealth = Float.NaN;
    private static final List<String> reports = new ArrayList<>();

    private DamageWatch() {}

    public static synchronized String drainReports() {
        if (reports.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String r : reports) b.append("[alert] ").append(r).append("\n");
        reports.clear();
        if (b.length() > 0 && b.charAt(b.length() - 1) == '\n') b.setLength(b.length() - 1);
        return b.toString();
    }

    /** Registered on ClientTickEvents.END_CLIENT_TICK. */
    public static synchronized void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) { lastHealth = Float.NaN; return; }
        LocalPlayer p = mc.player;
        float h = p.getHealth();
        if (!Float.isNaN(lastHealth) && h < lastHealth - 0.01f) {
            float lost = lastHealth - h;
            String msg = String.format(Locale.ROOT, "took %.1f damage (health %.1f -> %.1f)", lost, lastHealth, h);
            String src = nearestHostile(mc, p);
            if (src != null) msg = msg + "; nearest hostile " + src;
            if (h <= 0.0f) msg = msg + " -- DEAD";
            reports.add(msg);
        }
        lastHealth = h;
    }

    private static String nearestHostile(Minecraft mc, LocalPlayer p) {
        List<Mob> mobs = mc.level.getEntitiesOfClass(Mob.class,
                p.getBoundingBox().inflate(SOURCE_RADIUS), m -> m instanceof Enemy && m.isAlive());
        Mob best = null;
        double bestSq = Double.MAX_VALUE;
        for (Mob m : mobs) {
            double d = m.distanceToSqr(p);
            if (d < bestSq) { bestSq = d; best = m; }
        }
        if (best == null) return null;
        return best.getType().toShortString() + " " + (int) Math.round(Math.sqrt(bestSq)) + "m";
    }
}
