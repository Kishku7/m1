package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

/**
 * Reflex interrupt: defend the protectee from one attacker, then finish so the paused standing
 * plan (e.g. follow) resumes. Wraps {@link AttackAction} (id-targeted, crit mode -- approach,
 * cooldown timing, jump-crits) and adds the defense guardrails:
 *
 * <ul>
 *   <li><b>Leash is measured GUARD-to-TARGET, not me-to-guard.</b> The rule is "do not chase a
 *       mob far away from the person I am protecting", so the distance that matters is how far
 *       the MOB is from the guard. Beyond {@link #LEASH} we break off once and finish.</li>
 *   <li><b>Out of position means REGROUP, not break off.</b> When the guard himself has moved
 *       beyond the leash (he ran ahead and picked a fight), the old code measured MY distance to
 *       him and quit -- then the reflex fired again on his next hit, and again, producing pure
 *       {@code [agent]} spam and no defending at all (Master, 2026-08-01). Now we walk toward the
 *       guard, keeping the threat targeted, and say so exactly ONCE.</li>
 *   <li><b>Every advisory fires at most once per engagement.</b> An interrupt that talks more
 *       than it acts is worse than silence.</li>
 *   <li><b>Failure containment:</b> if the inner attack FAILS (unreachable, timeout), the target
 *       is blacklisted in {@link ThreatWatch} briefly so the reflex does not loop on it, and the
 *       defense completes DONE (an interrupt must never wedge the stack).</li>
 * </ul>
 */
public final class DefendAction implements MinecraftAction {

    private static final double LEASH = 16.0;
    /** Give up regrouping after this long without closing on the guard. */
    private static final int REGROUP_STALL_TICKS = 200;
    private static final double REGROUP_STOP = LEASH * 0.5;

    private final int targetId;
    private final String guardName;
    private final AttackAction inner;

    private boolean regrouping;
    private boolean saidRegroup;
    private boolean saidBreakOff;
    private int ticksRun;
    private double bestGuardDist = Double.MAX_VALUE;
    private int lastRegroupProgress;

    public DefendAction(int targetId, String guardName) {
        this.targetId = targetId;
        this.guardName = guardName;
        this.inner = new AttackAction(String.valueOf(targetId), true);
    }

    @Override
    public String name() {
        return "defend";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.level == null) {
            return StepResult.FAILED;
        }
        ticksRun++;

        // Target gone or down already?
        if (!(mc.level.getEntity(targetId) instanceof LivingEntity target)
                || !target.isAlive() || target.isRemoved()) {
            finishInner(ctx);
            ctx.report(ReportClass.STATUS, "defend: threat cleared, resuming");
            return StepResult.DONE;
        }

        Player guard = findGuard(mc);

        // TRUE leash: never chase a mob far away from the person being protected.
        if (guard != null && guard.distanceToSqr(target) > LEASH * LEASH) {
            finishInner(ctx);
            ThreatWatch.blacklist(targetId, ctx.tick());
            if (!saidBreakOff) {
                saidBreakOff = true;
                ctx.report(ReportClass.STATUS, "defend: " + target.getType().toShortString()
                        + " is beyond the " + (int) LEASH + "m leash from "
                        + guard.getName().getString() + " -- staying with him instead");
            }
            return StepResult.DONE;
        }

        // Out of position: close on the guard rather than abandoning the defense entirely.
        double guardDist = (guard == null) ? 0.0 : Math.sqrt(mc.player.distanceToSqr(guard));
        if (guard != null && guardDist > LEASH) {
            if (!saidRegroup) {
                saidRegroup = true;
                ctx.report(ReportClass.ADVISORY, String.format(Locale.ROOT,
                        "defend: %s is %.0fm away fighting %s -- closing to re-engage",
                        guard.getName().getString(), guardDist, target.getType().toShortString()));
            }
            if (guardDist < bestGuardDist - 1.0) {
                bestGuardDist = guardDist;
                lastRegroupProgress = ticksRun;
            } else if (bestGuardDist == Double.MAX_VALUE) {
                bestGuardDist = guardDist;
                lastRegroupProgress = ticksRun;
            } else if (ticksRun - lastRegroupProgress > REGROUP_STALL_TICKS) {
                finishInner(ctx);
                ThreatWatch.blacklist(targetId, ctx.tick());
                ctx.report(ReportClass.STATUS, "defend: cannot reach "
                        + guard.getName().getString() + " to help -- standing down");
                return StepResult.DONE;
            }
            if (!MoveControl.isActive()) {
                regrouping = true;
                ScreenOps.startMove(mc, mc.player, guard.getX(), guard.getY(), guard.getZ(),
                        REGROUP_STOP, "defend regroup");
            }
            return StepResult.RUNNING;
        }
        if (regrouping) {
            regrouping = false;
            MoveControl.stop();   // back in position: hand movement over to the attack loop
        }

        StepResult r = inner.step(ctx);
        if (r == StepResult.FAILED) {
            finishInner(ctx);
            ThreatWatch.blacklist(targetId, ctx.tick());
            ctx.report(ReportClass.STATUS, "defend: could not finish target id=" + targetId
                    + " (blacklisted briefly), resuming");
            return StepResult.DONE;
        }
        if (r == StepResult.DONE) {
            finishInner(ctx);
            ctx.report(ReportClass.STATUS, "defend: threat down, resuming");
        }
        return r;
    }

    /**
     * Tear the wrapped attack down on EVERY exit path.
     *
     * <p>Live bug (Master, 2026-08-01): pillagers attacked, defend engaged, {@link AttackAction}
     * auto-equipped the netherite sword -- and the pickaxe never came back. The restore added in
     * 0.14.0 lives in AttackAction's own cleanup, but defend's fast paths (threat died, leash
     * break-off, regroup give-up) return DONE WITHOUT ever calling into the inner action again, so
     * that cleanup never ran. The common case -- the reflex, not a typed `attack` -- was exactly the
     * case that leaked. Any wrapper that can finish on behalf of the action it wraps has to run the
     * wrapped action's cleanup itself.
     */
    private void finishInner(ActionContext ctx) {
        inner.onInterrupted(ctx);   // restores the pre-combat hotbar slot + releases movement/use
        MoveControl.stop();
    }

    private Player findGuard(Minecraft mc) {
        if (guardName == null) return null;
        for (Player p : mc.level.players()) {
            if (p != mc.player && guardName.equalsIgnoreCase(p.getName().getString())) {
                return p;
            }
        }
        return null;
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        inner.onInterrupted(ctx);
    }
}
