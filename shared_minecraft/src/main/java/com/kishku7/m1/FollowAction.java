package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Leaf action: follow a named player, keeping a STANDOFF of {@code dist} blocks. Never completes on
 * its own -- runs until interrupted / replaced ({@code stop}). Each step it resolves the target
 * player live and:
 *  - if within {@code dist}: stops and holds (never comes closer);
 *  - if within {@code dist + HYST} and already stopped: holds (deadband, no jitter);
 *  - if farther: pathfinds (vanilla A* via {@link ScreenOps#startMove}/{@link PathOracle}) to a
 *    standoff point {@code dist} from the player on our side, stopping there -- so it approaches by
 *    routing around walls and halts at range instead of running through the player.
 * Re-paths as the player moves, so it tracks a moving target and stays locked on until told to stop.
 */
public final class FollowAction implements MinecraftAction {

    private static final double FOLLOW_HYST = 1.5;     // deadband beyond standoff before we chase again
    private static final double REACQUIRE_DRIFT = 1.5; // re-path once the standoff point moves this far
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
                    + " (standoff " + (int) dist + "m)");
        }

        double gap = Math.sqrt(me.distanceToSqr(target));

        // Inside the standoff radius: stop and hold -- never come closer.
        if (gap <= dist) {
            if (MoveControl.isActive()) MoveControl.stop();
            return StepResult.RUNNING;
        }
        // Just outside the standoff and already stopped: stay put (deadband kills jitter).
        if (gap <= dist + FOLLOW_HYST && !MoveControl.isActive()) {
            return StepResult.RUNNING;
        }

        // Too far: pathfind to a standoff point `dist` from the player, on our side, and stop there.
        double vx = me.getX() - target.getX();
        double vz = me.getZ() - target.getZ();
        double vlen = Math.hypot(vx, vz);
        double sx;
        double sz;
        if (vlen < 0.5) {
            sx = target.getX();
            sz = target.getZ();
        } else {
            sx = target.getX() + vx / vlen * dist;
            sz = target.getZ() + vz / vlen * dist;
        }
        boolean needRepath = !MoveControl.isActive()
                || Math.hypot(MoveControl.targetX() - sx, MoveControl.targetZ() - sz) > REACQUIRE_DRIFT;
        if (needRepath) {
            ScreenOps.startMove(mc, me, sx, target.getY(), sz, 1.0, "follow " + who);
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
