package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Leaf action: melee a target with correct combat TIMING. This is the core of M1 combat -- not
 * "swing the sword" but a tick-stepped loop that respects the attack cooldown and times a jump-crit.
 *
 * <p>Per Master (2026-06-29): combat = timing, jumping at the right time for a critical, proper use of
 * a shield. Crit detection itself is automatic inside {@code Player.attack} (it checks falling /
 * not-sprinting / not-in-water + cooldown), so this action only has to: face the target, WAIT for the
 * weapon to recharge ({@link net.minecraft.world.entity.player.Player#getAttackStrengthScale}), and
 * in crit mode {@code jumpFromGround()} then strike on the way DOWN at >= 84.8% charge. Full design +
 * per-enemy handling: {@code projects/m1-combat.md}.
 *
 * <p>Target spec: {@code "nearest"} (nearest hostile within radius), an entity numeric id, or empty
 * (crosshair entity). Re-validates the target each step; finishes DONE when it dies, FAILED if it is
 * unreachable, gone, or the safety timeout fires.
 */
public final class AttackAction implements MinecraftAction {

    private static final double ACQUIRE_RADIUS = 12.0;
    private static final double REACH = 4.0;          // a touch beyond survival reach
    private static final float FULL = 0.95f;          // treat >= this as "charged" for a normal hit
    private static final float CRIT = 0.848f;         // crit needs >= 84.8% cooldown
    private static final int MAX_TICKS = 600;         // ~30s safety cap

    private final String spec;
    private final boolean crit;

    private LivingEntity target;
    private int phase;        // 0 = ready/recharging, 1 = jumped, awaiting descent
    private int ticksRun;
    private int hits;
    private boolean resolved;

    public AttackAction(String spec, boolean crit) {
        this.spec = (spec == null) ? "" : spec.trim();
        this.crit = crit;
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
            ctx.report(ReportClass.STATUS, "attack: timeout after " + hits + " hits");
            return StepResult.FAILED;
        }

        if (!resolved) {
            resolved = true;
            target = acquire(mc, p);
            if (target == null) {
                ctx.report(ReportClass.STATUS, "attack: no target (" + (spec.isEmpty() ? "crosshair" : spec) + ")");
                return StepResult.FAILED;
            }
            ctx.report(ReportClass.STATUS, "attack: engaging " + target.getType().toShortString()
                    + " (crit=" + crit + ")");
        }

        if (target == null || !target.isAlive() || target.isRemoved()) {
            ctx.report(ReportClass.STATUS, "attack: target down after " + hits + " hits");
            return StepResult.DONE;
        }

        double distSq = target.distanceToSqr(p);
        if (distSq > REACH * REACH) {
            // Auto-approach: walk to the target's live position, then strike. Re-path as it moves.
            boolean needRepath = !MoveControl.isActive()
                    || Math.hypot(MoveControl.targetX() - target.getX(), MoveControl.targetZ() - target.getZ()) > 2.0;
            if (needRepath) {
                ScreenOps.startMove(mc, p, target.getX(), target.getY(), target.getZ(), REACH - 0.5, "attack approach");
            }
            return StepResult.RUNNING;
        }
        if (MoveControl.isActive()) {
            MoveControl.stop(); // within reach now -- stop walking before we strike
        }

        faceEntity(p, target);
        float charge = p.getAttackStrengthScale(0.0f);

        if (crit) {
            if (phase == 0) {
                if (p.onGround() && charge >= FULL) {
                    p.jumpFromGround();
                    phase = 1;
                }
                return StepResult.RUNNING;
            }
            // phase 1: airborne after a jump
            if (!p.onGround() && p.getDeltaMovement().y < 0.0 && charge >= CRIT) {
                strike(mc, p, charge, true);
                phase = 0;
                return StepResult.RUNNING;
            }
            if (p.onGround()) {
                // landed without hitting the crit window; take a normal hit if charged, then retry
                phase = 0;
                if (charge >= FULL) {
                    strike(mc, p, charge, false);
                }
            }
            return StepResult.RUNNING;
        }

        // normal / sweep: just respect the cooldown
        if (charge >= FULL) {
            strike(mc, p, charge, false);
        }
        return StepResult.RUNNING;
    }

    private void strike(Minecraft mc, LocalPlayer p, float charge, boolean critWindow) {
        mc.gameMode.attack(p, target);
        p.swing(InteractionHand.MAIN_HAND);
        hits++;
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
        List<Mob> mobs = mc.level.getEntitiesOfClass(Mob.class,
                p.getBoundingBox().inflate(ACQUIRE_RADIUS),
                m -> m instanceof Enemy && m.isAlive());
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
        phase = 0; // drop any mid-jump state; re-acquire timing on resume
    }
}
