package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Reflex interrupt: defend the protectee from one attacker, then finish so the paused standing
 * plan (e.g. follow) resumes. Wraps {@link AttackAction} (id-targeted, crit mode -- approach,
 * cooldown timing, jump-crits) and adds the defense guardrails:
 *
 * <ul>
 *   <li><b>Leash:</b> while a guarded player is present, never chase the target further than
 *       {@link #LEASH} blocks from the guard -- protecting them beats chasing a fleeing mob.
 *       Breaks off, reports, and finishes (follow then walks us back).</li>
 *   <li><b>Failure containment:</b> if the inner attack FAILS (unreachable, timeout), the target
 *       is blacklisted in {@link ThreatWatch} briefly so the reflex does not loop on it, and the
 *       defense completes DONE (an interrupt must never wedge the stack).</li>
 * </ul>
 */
public final class DefendAction implements MinecraftAction {

    private static final double LEASH = 16.0;

    private final int targetId;
    private final String guardName;
    private final AttackAction inner;

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

        // Target gone or down already?
        if (!(mc.level.getEntity(targetId) instanceof LivingEntity target)
                || !target.isAlive() || target.isRemoved()) {
            MoveControl.stop();
            ctx.report(ReportClass.STATUS, "defend: threat cleared, resuming");
            return StepResult.DONE;
        }

        // Leash: never stray too far from whoever we are protecting.
        Player guard = findGuard(mc);
        if (guard != null && mc.player.distanceToSqr(guard) > LEASH * LEASH) {
            MoveControl.stop();
            ThreatWatch.blacklist(targetId, ctx.tick());
            ctx.report(ReportClass.STATUS, "defend: breaking off (leash " + (int) LEASH
                    + "m from " + guard.getName().getString() + "), resuming");
            return StepResult.DONE;
        }

        StepResult r = inner.step(ctx);
        if (r == StepResult.FAILED) {
            MoveControl.stop();
            ThreatWatch.blacklist(targetId, ctx.tick());
            ctx.report(ReportClass.STATUS, "defend: could not finish target id=" + targetId
                    + " (blacklisted briefly), resuming");
            return StepResult.DONE;
        }
        if (r == StepResult.DONE) {
            MoveControl.stop();
            ctx.report(ReportClass.STATUS, "defend: threat down, resuming");
        }
        return r;
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
