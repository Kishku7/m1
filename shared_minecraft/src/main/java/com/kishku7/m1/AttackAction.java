package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Leaf action: engage ANY mob with correct combat timing -- the universal combat model
 * (Master, 2026-07-02: "make it robust, make it work for any mob anywhere"; dragon/wither
 * excepted for later). Design + per-enemy table: projects/m1-combat.md.
 *
 * What the loop owns (tick-rate, mod-reactive per m1-agent.md sec 5-7):
 *  - AUTO-EQUIP: picks the best weapon in the hotbar at engage (702b coaching: "switch to a
 *    weapon"); re-equips when switching between bow range and melee.
 *  - MELEE timing: never swing under ~full charge; jump-crit cadence (jump, strike on descent
 *    at >=84.8%); sweep falls out of grounded full-charge swings with a sword.
 *  - SPEAR (26.x): PIERCING_WEAPON stab via gameMode.piercingAttack -- full charge only, reach
 *    band 2.0-4.5, so the loop holds a 3-4 block band instead of closing to sword range.
 *  - BOW: full 20-tick draw, ballistic pitch (v0=3.0/t, g=0.05, drag 0.99 -- pinned from deobf)
 *    with target leading; used when the target is far, unreachable, or mode=ranged.
 *  - CREEPER policy: sprint-charge, ONE full-charge knockback hit, retreat out of blast range,
 *    repeat. Never lingers in the swell radius.
 *  - RANGED-MOB policy (skeleton/stray/bogged/pillager): shield-advance -- off-hand shield held
 *    while closing (5t raise delay pinned), dropped to strike in reach.
 *
 * Target spec: "nearest", an entity id, or empty (crosshair). Modes: crit (default) | normal |
 * ranged. DONE when the target dies; FAILED on timeout/unreachable-with-no-bow.
 */
public final class AttackAction implements MinecraftAction {

    private static final double ACQUIRE_RADIUS = 32.0; // bow range included (was 12)
    private static final double REACH = 4.0;
    private static final float FULL = 0.95f;
    private static final float CRIT = 0.848f;
    private static final int MAX_TICKS = 1200;         // ~60s: ranged fights run longer
    private static final int BOW_DRAW_TICKS = 22;      // 20t full charge + safety
    private static final double BOW_ENGAGE_DIST = 12.0;
    private static final double BOW_MELEE_SWITCH = 8.0;
    private static final double SPEAR_MIN = 2.2;
    private static final double SPEAR_MAX = 4.3;
    private static final double CREEPER_DANGER = 4.5;  // swell radius 3 + margin
    private static final double CREEPER_RESET = 6.5;

    private enum Mode { CRIT, NORMAL, RANGED }

    private final String spec;
    private final Mode mode;

    private LivingEntity target;
    private int phase;            // melee: 0 ready, 1 airborne post-jump
    private int ticksRun;
    private int hits;
    private int shots;
    private boolean resolved;
    private boolean equipped;
    private boolean usingBow;
    private boolean retreating;
    private boolean shieldUp;
    private boolean spearFallback;
    private int pinnedTicks;
    private int lastRetreatTick;
    private String policy = "default";

    public AttackAction(String spec, boolean crit) {
        this(spec, crit ? "crit" : "normal");
    }

    public AttackAction(String spec, String mode) {
        this.spec = (spec == null) ? "" : spec.trim();
        String m = mode == null ? "crit" : mode.trim().toLowerCase(Locale.ROOT);
        this.mode = m.equals("ranged") ? Mode.RANGED : m.equals("normal") ? Mode.NORMAL : Mode.CRIT;
    }

    @Override
    public String name() {
        return "attack";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.level == null) {
            ctx.report(ReportClass.STATUS, "attack: not in world");
            return StepResult.FAILED;
        }
        LocalPlayer p = mc.player;

        if (++ticksRun > MAX_TICKS) {
            release(mc, p);
            ctx.report(ReportClass.STATUS, "attack: timeout after " + hits + " hits, " + shots + " shots");
            return StepResult.FAILED;
        }

        if (!resolved) {
            resolved = true;
            target = acquire(mc, p);
            if (target == null) {
                ctx.report(ReportClass.STATUS, "attack: no target ("
                        + (spec.isEmpty() ? "crosshair" : spec) + ")");
                return StepResult.FAILED;
            }
            policy = policyFor(typeId(target));
            ctx.report(ReportClass.STATUS, "attack: engaging " + target.getType().toShortString()
                    + " (mode=" + mode.name().toLowerCase(Locale.ROOT) + ", policy=" + policy + ")");
        }

        if (target == null || !target.isAlive() || target.isRemoved()) {
            release(mc, p);
            ctx.report(ReportClass.STATUS, "attack: target down after " + hits + " hits"
                    + (shots > 0 ? ", " + shots + " arrows" : ""));
            return StepResult.DONE;
        }

        double dist = Math.sqrt(target.distanceToSqr(p));

        // ---- CREEPER: hit-and-back. Never linger in the blast radius. ----
        if (policy.equals("creeper")) {
            return creeperStep(ctx, mc, p, dist);
        }

        // ---- BOW: far / forced-ranged / unreachable targets. ----
        boolean wantBow = mode == Mode.RANGED
                || (mode != Mode.RANGED && dist > BOW_ENGAGE_DIST)
                || policy.equals("ranged-pref");
        if (wantBow && CombatOps.bowSlot(p) >= 0 && CombatOps.arrowCount(p) > 0
                && (mode == Mode.RANGED || dist > BOW_MELEE_SWITCH)) {
            return bowStep(ctx, mc, p, dist);
        }
        if (usingBow) {
            // switching back to melee
            if (p.isUsingItem()) {
                mc.gameMode.releaseUsingItem(p);
            }
            usingBow = false;
            equipped = false;
        }

        // ---- MELEE ----
        if (!equipped) {
            equipped = true;
            int slot = CombatOps.bestMeleeSlot(p);
            if (slot >= 0 && slot != InventoryCompat.getSelected(p.getInventory())) {
                CombatOps.hold(mc, slot);
            }
            ctx.report(ReportClass.STATUS, "attack: weapon = "
                    + BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath());
        }

        boolean spear = !spearFallback && CombatOps.holdingSpear(p);
        double stopAt = spear ? 3.2 : REACH - 0.5;
        double strikeRange = spear ? SPEAR_MAX : REACH;

        if (dist > strikeRange) {
            // Shield-advance vs ranged mobs: keep the shield up while closing.
            if (policy.equals("shield-advance") && p.getOffhandItem().is(Items.SHIELD)) {
                faceEntity(p, target);
                if (!p.isUsingItem()) {
                    mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
                    shieldUp = true;
                }
            }
            boolean needRepath = !MoveControl.isActive()
                    || Math.hypot(MoveControl.targetX() - target.getX(),
                            MoveControl.targetZ() - target.getZ()) > 2.0;
            if (needRepath) {
                String r = ScreenOps.startMove(mc, p, target.getX(), target.getY(), target.getZ(),
                        stopAt, "attack approach");
                if (!r.startsWith("OK") && CombatOps.bowSlot(p) >= 0 && CombatOps.arrowCount(p) > 0) {
                    // Unreachable (flying / across a gap): fight with the bow instead.
                    return bowStep(ctx, mc, p, dist);
                }
            }
            return StepResult.RUNNING;
        }
        if (MoveControl.isActive()) {
            MoveControl.stop();
        }
        if (shieldUp && p.isUsingItem()) {
            mc.gameMode.releaseUsingItem(p); // drop the shield to strike
            shieldUp = false;
        }

        faceEntity(p, target);
        float charge = p.getAttackStrengthScale(0.0f);

        // Spear (learned the hard way, one bot death 2026-07-02): STAB FIRST whenever in band and
        // charged -- movement state is irrelevant, reach is 4.5. Retreat only when inside the 2.0
        // minimum, on a cooldown; if the target stays glued (melee chaser), switch to a close
        // weapon instead of kiting forever.
        if (spear) {
            // Attempt the stab whenever charged and plausibly in range -- the server judges the
            // 2.0-4.5 reach along the ray (center-distance is NOT the same measure; do not over-gate).
            if (charge >= 0.99f && dist <= SPEAR_MAX + 0.5) {
                if (M1Compat.tryPiercingAttack(mc, p)) {
                    hits++;
                }
                // fall through: if inside the minimum we ALSO manage distance below
            }
            if (dist < SPEAR_MIN) {
                pinnedTicks++;
                if (pinnedTicks > 15) {
                    spearFallback = true;
                    int alt = CombatOps.bestMeleeSlot(p, true);
                    if (alt >= 0) {
                        CombatOps.hold(mc, alt);
                        ctx.report(ReportClass.STATUS, "attack: target inside spear minimum reach"
                                + " -- switching to " + BuiltInRegistries.ITEM.getKey(
                                        p.getInventory().getItem(alt).getItem()).getPath());
                    } else {
                        spearFallback = false; // nothing better: keep the spear, keep desperation-stabbing
                        pinnedTicks = -40;     // and only re-evaluate in 2s
                        ctx.report(ReportClass.STATUS, "attack: pinned with only a spear -- holding it");
                    }
                    return StepResult.RUNNING;
                }
                if (!MoveControl.isActive() && ticksRun - lastRetreatTick > 10) {
                    lastRetreatTick = ticksRun;
                    retreatFrom(mc, p, target, 4.0);
                }
                return StepResult.RUNNING;
            }
            pinnedTicks = 0;
            if (MoveControl.isActive()) {
                MoveControl.stop(); // in band: hold and wait for charge
            }
            return StepResult.RUNNING;
        }

        if (mode != Mode.NORMAL) {
            if (phase == 0) {
                if (p.onGround() && charge >= FULL) {
                    p.jumpFromGround();
                    phase = 1;
                }
                return StepResult.RUNNING;
            }
            if (!p.onGround() && p.getDeltaMovement().y < 0.0 && charge >= CRIT) {
                strike(mc, p);
                phase = 0;
                return StepResult.RUNNING;
            }
            if (p.onGround()) {
                phase = 0;
                if (charge >= FULL) {
                    strike(mc, p);
                }
            }
            return StepResult.RUNNING;
        }

        if (charge >= FULL) {
            strike(mc, p);
        }
        return StepResult.RUNNING;
    }

    // ---- creeper: sprint in, one KB hit at full charge, back out, repeat ----
    private StepResult creeperStep(ActionContext ctx, Minecraft mc, LocalPlayer p, double dist) {
        if (!equipped) {
            equipped = true;
            int slot = CombatOps.bestMeleeSlot(p);
            if (slot >= 0 && slot != InventoryCompat.getSelected(p.getInventory())) {
                CombatOps.hold(mc, slot);
            }
            ctx.report(ReportClass.STATUS, "attack: creeper policy -- hit-and-back (never linger in blast range)");
        }
        float charge = p.getAttackStrengthScale(0.0f);
        if (retreating) {
            // The KB hit is what breaks the swell: if recharged and it is on us, turn and strike.
            if (charge >= FULL && dist <= REACH) {
                MoveControl.stop();
                retreating = false;
                faceEntity(p, target);
                strike(mc, p);
                retreatFrom(mc, p, target, 8.0);
                retreating = true;
                return StepResult.RUNNING;
            }
            if (dist < CREEPER_RESET && MoveControl.isActive()) {
                return StepResult.RUNNING;
            }
            retreating = false;
            MoveControl.stop();
        }
        if (dist <= REACH && charge >= FULL) {
            faceEntity(p, target);
            strike(mc, p);                       // sprint-KB lands when we arrived at a sprint
            retreatFrom(mc, p, target, 8.0);
            retreating = true;
            return StepResult.RUNNING;
        }
        if (dist < CREEPER_DANGER && charge < FULL) {
            retreatFrom(mc, p, target, 8.0);     // not ready to hit: do not stand in the swell radius
            retreating = true;
            return StepResult.RUNNING;
        }
        if (dist > REACH) {
            MoveControl.setSprint(true);         // sprint approach -> knockback hit
            boolean needRepath = !MoveControl.isActive()
                    || Math.hypot(MoveControl.targetX() - target.getX(),
                            MoveControl.targetZ() - target.getZ()) > 2.0;
            if (needRepath) {
                ScreenOps.startMove(mc, p, target.getX(), target.getY(), target.getZ(),
                        REACH - 1.0, "creeper charge");
            }
        }
        return StepResult.RUNNING;
    }

    // ---- bow: full draw, ballistic pitch + lead, release ----
    private StepResult bowStep(ActionContext ctx, Minecraft mc, LocalPlayer p, double dist) {
        if (!usingBow) {
            int slot = CombatOps.bowSlot(p);
            if (slot != InventoryCompat.getSelected(p.getInventory())) {
                CombatOps.hold(mc, slot);
            }
            usingBow = true;
            equipped = false;
            ctx.report(ReportClass.STATUS, "attack: bow engaged (" + CombatOps.arrowCount(p)
                    + " arrows, " + String.format(Locale.ROOT, "%.0f", dist) + "m)");
        }
        // NO BLIND FIRE (learned live 2026-07-02: emptied a quiver into a wall): only draw with
        // line of sight; otherwise reposition toward the target until we can see it.
        if (!p.hasLineOfSight(target)) {
            if (p.isUsingItem()) {
                mc.gameMode.releaseUsingItem(p);
            }
            boolean needRepath = !MoveControl.isActive()
                    || Math.hypot(MoveControl.targetX() - target.getX(),
                            MoveControl.targetZ() - target.getZ()) > 2.0;
            if (needRepath) {
                ScreenOps.startMove(mc, p, target.getX(), target.getY(), target.getZ(),
                        BOW_MELEE_SWITCH - 1.0, "bow reposition");
            }
            return StepResult.RUNNING;
        }
        if (MoveControl.isActive()) {
            MoveControl.stop();
        }
        aimBow(p, dist);
        if (!p.isUsingItem()) {
            if (p.getAttackStrengthScale(0.0f) >= FULL) {
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND); // start the draw
            }
            return StepResult.RUNNING;
        }
        if (p.getTicksUsingItem() >= BOW_DRAW_TICKS) {
            aimBow(p, dist);
            mc.gameMode.releaseUsingItem(p);
            shots++;
        }
        return StepResult.RUNNING;
    }

    private void aimBow(LocalPlayer p, double dist) {
        // lead the target by its velocity over the arrow's flight time
        int flight = CombatOps.bowFlightTicks(dist);
        Vec3 vel = target.getDeltaMovement();
        double tx = target.getX() + vel.x * flight;
        double tz = target.getZ() + vel.z * flight;
        double ty = target.getY() + target.getBbHeight() * 0.5;
        Vec3 eye = p.getEyePosition();
        double dx = tx - eye.x;
        double dz = tz - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot(CombatOps.bowPitchFor(h, ty - eye.y));
    }

    private void retreatFrom(Minecraft mc, LocalPlayer p, LivingEntity threat, double blocks) {
        double vx = p.getX() - threat.getX();
        double vz = p.getZ() - threat.getZ();
        double len = Math.hypot(vx, vz);
        if (len < 0.3) {
            vx = 1;
            vz = 0;
            len = 1;
        }
        double rx = p.getX() + vx / len * blocks;
        double rz = p.getZ() + vz / len * blocks;
        MoveControl.setSprint(true);
        ScreenOps.startMove(mc, p, rx, p.getY(), rz, 1.5, "combat retreat");
    }

    private void strike(Minecraft mc, LocalPlayer p) {
        mc.gameMode.attack(p, target);
        p.swing(InteractionHand.MAIN_HAND);
        hits++;
    }

    private void release(Minecraft mc, LocalPlayer p) {
        if (p.isUsingItem()) {
            mc.gameMode.releaseUsingItem(p);
        }
        MoveControl.setSprint(false);
        if (MoveControl.isActive()) {
            MoveControl.stop();
        }
    }

    /** Aim at the target's mid-body. */
    private static void faceEntity(LocalPlayer p, LivingEntity e) {
        Vec3 eye = p.getEyePosition();
        double tx = e.getX();
        double ty = e.getY() + e.getBbHeight() * 0.5;
        double tz = e.getZ();
        double dx = tx - eye.x;
        double dy = ty - eye.y;
        double dz = tz - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot((float) (-Math.toDegrees(Math.atan2(dy, h))));
    }

    private static String typeId(LivingEntity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    /** Per-enemy policy seed (m1-combat.md section 3). */
    private static String policyFor(String id) {
        switch (id) {
            case "creeper":
                return "creeper";
            case "skeleton":
            case "stray":
            case "bogged":
            case "pillager":
                return "shield-advance";
            case "blaze":
            case "ghast":
            case "breeze":
                return "ranged-pref";
            default:
                return "default";
        }
    }

    private LivingEntity acquire(Minecraft mc, LocalPlayer p) {
        if (spec.isEmpty()) {
            if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity le) {
                return le;
            }
            return null;
        }
        if (!"nearest".equalsIgnoreCase(spec)) {
            try {
                int id = Integer.parseInt(spec);
                if (mc.level.getEntity(id) instanceof LivingEntity le) {
                    return le;
                }
                return null;
            } catch (NumberFormatException ignored) {
                // fall through to nearest
            }
        }
        // "nearest" NEVER pre-emptively picks a SAFE-LIST (neutral) mob -- unless that mob is an
        // active threat (it attacked us; ThreatWatch tracks it). Explicit id/crosshair targets
        // remain allowed: that is the Master/AI override. (Master rule, 2026-07-02.)
        List<Mob> mobs = mc.level.getEntitiesOfClass(Mob.class,
                p.getBoundingBox().inflate(ACQUIRE_RADIUS),
                m -> m instanceof Enemy && m.isAlive()
                        && (!CombatOps.isSafeMob(m) || ThreatWatch.isThreat(m.getId())));
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Mob m : mobs) {
            double d = m.distanceToSqr(p);
            if (d < bestSq) {
                bestSq = d;
                best = m;
            }
        }
        return best;
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        phase = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            release(mc, mc.player);
        }
    }
}
