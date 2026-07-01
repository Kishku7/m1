package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Leaf action: follow a named player, keeping within {@code dist} blocks. Never completes on its own
 * -- it runs until interrupted / replaced ({@code agent stop} or the top-level {@code stop}). Each
 * step: resolve the target player live; if it is beyond {@code dist} and we are not already pathing
 * near its current spot, (re)issue a walk to its live position with arrival tolerance = dist.
 * Re-paths as the player moves, so it tracks a moving target.
 */
public final class FollowAction implements MinecraftAction {

    private static final double REACQUIRE_DRIFT = 2.0; // re-path if target drifts >2m from our goal
    private static final int LOST_GRACE_TICKS = 100;   // ~5s out-of-sight grace before giving up

    private final String who;
    private final double dist;
    private int lostTicks;
    private boolean announced;

    public FollowAction(String who, double dist) {
        this.who = (who == null) ? "" : who.trim();
        this.dist = Math.max(1.0, dist);
    }

    @Override
    public String name() {
        return "follow";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.level == null) {
            ctx.report(ReportClass.STATUS, "follow: not in world");
            return StepResult.FAILED;
        }
        LocalPlayer me = mc.player;
        Player target = findPlayer(mc, who);
        if (target == null) {
            if (++lostTicks > LOST_GRACE_TICKS) {
                ctx.report(ReportClass.STATUS, "follow: '" + who + "' not visible; giving up");
                MoveControl.stop();
                return StepResult.FAILED;
            }
            return StepResult.RUNNING;
        }
        lostTicks = 0;
        if (!announced) {
            announced = true;
            ctx.report(ReportClass.STATUS, "follow: tracking " + target.getName().getString()
                    + " (keep within " + (int) dist + "m)");
        }

        double gap = Math.sqrt(me.distanceToSqr(target));
        if (gap <= dist + 0.5) {
            if (MoveControl.isActive()) MoveControl.stop(); // close enough -- hold position
            return StepResult.RUNNING;
        }

        boolean needRepath = !MoveControl.isActive()
                || Math.hypot(MoveControl.targetX() - target.getX(), MoveControl.targetZ() - target.getZ()) > REACQUIRE_DRIFT;
        if (needRepath) {
            ScreenOps.startMove(mc, me, target.getX(), target.getY(), target.getZ(), dist, "follow " + who);
        }
        return StepResult.RUNNING;
    }

    private static Player findPlayer(Minecraft mc, String who) {
        for (Player pl : mc.level.players()) {
            if (pl == mc.player) continue;
            if (pl.getName().getString().equalsIgnoreCase(who)) return pl;
        }
        return null;
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        MoveControl.stop();
    }
}
