package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.InterruptSource;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The reflex sensor layer (fills the {@link InterruptSource} seam in {@link AgentRuntime}).
 * Auto-defense per Kishku7 (2026-07-01): if a mob attacks the MASTER or the AI player, the AI player
 * takes immediate action to defend -- mod-side, no AI round-trip -- then the interrupted standing
 * plan (e.g. follow) resumes automatically via the {@code ActionQueue} pause/resume contract.
 *
 * <p>Detection is poll-based (no mixin, version-stable): a {@link LivingEntity#hurtTime} rising
 * edge on the AI player or the guarded player means "was just hit". Attribution is a client-side
 * heuristic (the server does not sync attacker ids without a packet hook): prefer the nearest
 * living hostile within melee range of the victim; otherwise the nearest hostile within
 * {@link #RANGED_RADIUS} that has line of sight and is facing the victim (covers skeletons).
 *
 * <p>Guarded player defaults to the chat master ({@link ChatWatch#master()}); {@code defend
 * <player>} overrides, {@code defend auto} reverts. Hostiles that approach within
 * {@link #WARN_RADIUS} of either protectee are also reported as early-warning [agent] lines
 * ("more automatic hostile identification") without engaging until damage occurs.
 *
 * <p>Engagement is one {@link DefendAction} at a time (the interrupt stack is checked); remaining
 * recent threats are engaged in turn as each completes, nearest first. Targets that a defense
 * FAILED against (unreachable / timeout) are blacklisted briefly to avoid engage loops.
 */
public final class ThreatWatch implements InterruptSource {

    private static final double MELEE_RADIUS = 6.0;
    private static final double RANGED_RADIUS = 24.0;
    private static final double WARN_RADIUS = 10.0;
    private static final long THREAT_TTL_TICKS = 300;   // remember an attacker ~15s
    private static final long WARN_COOLDOWN_TICKS = 300; // one early-warning line per mob per ~15s
    private static final long BLACKLIST_TICKS = 200;

    private static volatile boolean enabled = true;
    private static volatile String guardOverride = null;

    private static int prevSelfHurt;
    private static int prevGuardHurt;
    private static String prevGuardName;

    /** attacker entity id -> expiry tick. */
    private static final Map<Integer, Long> threats = new HashMap<>();
    /** mob id -> next tick an early-warning line may fire for it. */
    private static final Map<Integer, Long> warned = new HashMap<>();
    /** entity id -> tick until which it must not be re-engaged (defense failed on it). */
    private static final Map<Integer, Long> blacklist = new HashMap<>();

    @Override
    public List<MinecraftAction> poll(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.level == null) {
            prevSelfHurt = 0;
            prevGuardHurt = 0;
            threats.clear();
            warned.clear();
            return Collections.emptyList();
        }
        if (!enabled) {
            return Collections.emptyList();
        }
        long tick = ctx.tick();
        LocalPlayer self = mc.player;
        Player guard = resolveGuard(mc, self);

        // --- rising-edge hit detection (self + guard) ---
        int selfHurt = self.hurtTime;
        if (selfHurt > prevSelfHurt && selfHurt >= 8) {
            onHit(mc, ctx, self, "me", tick);
        }
        prevSelfHurt = selfHurt;

        String gName = (guard == null) ? null : guard.getName().getString();
        if (gName != null && gName.equals(prevGuardName) && guard != null) {
            int gHurt = guard.hurtTime;
            if (gHurt > prevGuardHurt && gHurt >= 8) {
                onHit(mc, ctx, guard, gName, tick);
            }
            prevGuardHurt = gHurt;
        } else {
            prevGuardHurt = (guard == null) ? 0 : guard.hurtTime; // guard changed/appeared: re-baseline
        }
        prevGuardName = gName;

        // --- early-warning proximity scan (identification, not engagement) ---
        earlyWarn(mc, ctx, self, guard, tick);

        // --- expire old state ---
        expire(threats, tick);
        expire(blacklist, tick);

        // --- engage: one defense at a time, nearest live threat first ---
        if (AgentRuntime.queue().hasInterrupt() || threats.isEmpty()) {
            return Collections.emptyList();
        }
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        Vec3 anchor = (guard != null) ? guard.position() : self.position();
        for (Integer id : threats.keySet()) {
            if (blacklist.containsKey(id)) continue;
            if (!(mc.level.getEntity(id) instanceof LivingEntity le) || !le.isAlive() || le.isRemoved()) continue;
            double d = le.position().distanceToSqr(anchor);
            if (d < bestSq) {
                bestSq = d;
                best = le;
            }
        }
        if (best == null) {
            return Collections.emptyList();
        }
        threats.remove(best.getId());
        ctx.report(ReportClass.STATUS, "defend: engaging " + best.getType().toShortString()
                + " id=" + best.getId() + " (" + (int) Math.sqrt(bestSq) + "m from "
                + (guard != null ? guard.getName().getString() : "me") + ")");
        return List.of(new DefendAction(best.getId(), guard != null ? guard.getName().getString() : null));
    }

    /** A protectee took a hit: attribute it and remember the attacker. */
    private static void onHit(Minecraft mc, ActionContext ctx, LivingEntity victim, String who, long tick) {
        Mob attacker = attribute(mc, victim);
        if (attacker == null) {
            ctx.report(ReportClass.STATUS, "defend: " + who + " was hit (attacker unknown)");
            return;
        }
        Long old = threats.put(attacker.getId(), tick + THREAT_TTL_TICKS);
        if (old == null) {
            ctx.report(ReportClass.STATUS, "defend: " + who + " was hit by "
                    + attacker.getType().toShortString() + " id=" + attacker.getId());
        }
    }

    /** Nearest hostile in melee range of the victim, else nearest facing hostile with LOS in ranged range. */
    private static Mob attribute(Minecraft mc, LivingEntity victim) {
        List<Mob> near = mc.level.getEntitiesOfClass(Mob.class,
                victim.getBoundingBox().inflate(RANGED_RADIUS), m -> m instanceof Enemy && m.isAlive());
        Mob melee = null;
        double meleeSq = MELEE_RADIUS * MELEE_RADIUS;
        Mob ranged = null;
        double rangedSq = Double.MAX_VALUE;
        for (Mob m : near) {
            double d = m.distanceToSqr(victim);
            if (d <= meleeSq) {
                meleeSq = d;
                melee = m;
            } else if (d < rangedSq && isFacing(m, victim) && m.hasLineOfSight(victim)) {
                rangedSq = d;
                ranged = m;
            }
        }
        return (melee != null) ? melee : ranged;
    }

    private static boolean isFacing(Mob m, LivingEntity victim) {
        Vec3 view = m.getViewVector(1.0f).normalize();
        Vec3 to = victim.position().subtract(m.position()).normalize();
        return view.dot(to) > 0.5;
    }

    private static void earlyWarn(Minecraft mc, ActionContext ctx, LocalPlayer self, Player guard, long tick) {
        List<Mob> near = mc.level.getEntitiesOfClass(Mob.class,
                self.getBoundingBox().inflate(WARN_RADIUS + 24.0), m -> m instanceof Enemy && m.isAlive());
        for (Mob m : near) {
            double dSelf = Math.sqrt(m.distanceToSqr(self));
            double dGuard = (guard != null) ? Math.sqrt(m.distanceToSqr(guard)) : Double.MAX_VALUE;
            double d = Math.min(dSelf, dGuard);
            if (d > WARN_RADIUS) continue;
            Long next = warned.get(m.getId());
            if (next != null && tick < next) continue;
            warned.put(m.getId(), tick + WARN_COOLDOWN_TICKS);
            String who = (dGuard < dSelf && guard != null) ? guard.getName().getString() : "me";
            ctx.report(ReportClass.STATUS, String.format(Locale.ROOT,
                    "hostile nearby: %s id=%d %dm from %s",
                    m.getType().toShortString(), m.getId(), (int) d, who));
        }
        if (warned.size() > 64) {
            expire(warned, tick);
        }
    }

    private static void expire(Map<Integer, Long> map, long tick) {
        Iterator<Map.Entry<Integer, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= tick) it.remove();
        }
    }

    /** The player being guarded: the override name if set, else the chat master. Null if absent. */
    private static Player resolveGuard(Minecraft mc, LocalPlayer self) {
        String name = guardOverride;
        if (name == null) name = ChatWatch.master();
        if (name == null) return null;
        for (Player p : mc.level.players()) {
            if (p != self && name.equalsIgnoreCase(p.getName().getString())) {
                return p;
            }
        }
        return null;
    }

    /** Called by {@link DefendAction} when a defense fails so the target is not immediately re-engaged. */
    static void blacklist(int entityId, long nowTick) {
        blacklist.put(entityId, nowTick + BLACKLIST_TICKS);
        threats.remove(entityId);
    }

    /** {@code defend} socket command: on | off | status | auto | <player>. */
    public static String command(String rest) {
        String s = (rest == null) ? "" : rest.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("status")) {
            String guard = (guardOverride != null) ? guardOverride
                    : (ChatWatch.master() != null ? ChatWatch.master() + " (master)" : "(none -- no master yet)");
            return "defend: " + (enabled ? "ON" : "OFF") + ", guarding " + guard
                    + ", threats=" + threats.size();
        }
        switch (s.toLowerCase(Locale.ROOT)) {
            case "on":
                enabled = true;
                return "defend: ON";
            case "off":
                enabled = false;
                threats.clear();
                return "defend: OFF";
            case "auto":
                guardOverride = null;
                return "defend: guarding the chat master ("
                        + (ChatWatch.master() != null ? ChatWatch.master() : "none yet") + ")";
            default:
                guardOverride = s;
                return "defend: guarding " + s;
        }
    }
}
