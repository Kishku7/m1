package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Leaf action that walks the player to a target via M1's existing {@link MoveControl} pathing.
 *
 * <p>Phase 3 vertical slice: proves the agent queue can drive a real, world-affecting action through
 * the same proven code the {@code goto}/{@code moveto} socket commands already use. On its first step
 * it starts the move (reusing {@link ScreenOps#startMove}); thereafter it returns RUNNING while
 * {@link MoveControl#isActive()}, then DONE on "arrived" or FAILED on "blocked"/"timeout"/no-path.
 * The actual stepping of the walk is done by MoveControl's own client-tick handler; this action just
 * starts it and monitors it.
 *
 * <p>If a reflex interrupt preempts it, {@link #onInterrupted} stops the walk and clears the started
 * flag, so on resume it re-paths from the new position -- the lazy "re-read the world on resume"
 * behavior the design calls for.
 *
 * <p>A NaN {@code y} means "resolve the target Y from the player at start" (used by {@code moveto}).
 */
public final class MoveAction implements MinecraftAction {

    private final double x;
    private final double y;
    private final double z;
    private final double stop;
    private final String label;
    private boolean started;

    public MoveAction(double x, double y, double z, double stop, String label) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.stop = stop;
        this.label = label;
    }

    @Override
    public String name() {
        return "move";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.level == null) {
            ctx.report(ReportClass.STATUS, label + ": not in world");
            return StepResult.FAILED;
        }
        LocalPlayer p = mc.player;

        if (!started) {
            started = true;
            double ty = Double.isNaN(y) ? p.getY() : y;
            String r = ScreenOps.startMove(mc, p, x, ty, z, stop, label);
            ctx.report(ReportClass.STATUS, r);
            return MoveControl.isActive() ? StepResult.RUNNING : StepResult.FAILED;
        }

        if (MoveControl.isActive()) {
            return StepResult.RUNNING;
        }
        String st = MoveControl.status();
        if ("arrived".equals(st)) {
            ctx.report(ReportClass.STATUS, label + ": arrived");
            return StepResult.DONE;
        }
        ctx.report(ReportClass.STATUS, label + ": ended (" + st + ")");
        return StepResult.FAILED;
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        MoveControl.stop();   // hold position while the reflex runs
        started = false;      // re-path from the new position when resumed
    }
}
