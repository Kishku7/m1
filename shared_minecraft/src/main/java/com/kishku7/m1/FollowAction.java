package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.resources.ResourceKey;

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

    private static final double SPRINT_ON_FACTOR = 2.0;  // sprint when gap > 2x standoff (Kishku7 2026-07-01)
    private static final double SPRINT_OFF_FACTOR = 1.5; // stop sprinting once back inside 1.5x
    private static final int PORTAL_SCAN_RADIUS = 4;     // blocks around last-seen pos to find a portal
    private static final int PORTAL_GRACE_TICKS = 400;   // ~20s to transit + re-acquire on the other side

    private final String who;
    private final double dist;
    private int lostTicks;
    private boolean announced;

    // Portal following state
    private double seenX, seenY, seenZ;             // target's last seen position
    private boolean everSeen;
    private ResourceKey<Level> lastDim;             // our own dimension last step (transit detection)
    private BlockPos portalGoal;                    // portal block we are walking into (null = none)
    private boolean endTransit;                     // the portal we entered was an END portal

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

        // Dimension-transit detection (we just went through a portal ourselves).
        ResourceKey<Level> dim = mc.level.dimension();
        if (lastDim != null && dim != lastDim) {
            lastDim = dim;
            portalGoal = null;
            MoveControl.stop();
            if (endTransit) {
                // CRITICAL (Kishku7 2026-07-01): after an END portal transit, stand absolutely still.
                ctx.report(ReportClass.STATUS, "follow: passed through END portal -- AUTO-STOP (holding position)");
                return StepResult.DONE;
            }
            ctx.report(ReportClass.STATUS, "follow: arrived in " + dim.identifier() + "; re-acquiring " + who);
            lostTicks = -(PORTAL_GRACE_TICKS - LOST_GRACE_TICKS); // extended grace on the far side
            everSeen = false;
            return StepResult.RUNNING;
        }
        lastDim = dim;

        Player target = findPlayer(mc, who);
        if (target == null) {
            // Walking into a portal the target was last seen at?
            if (portalGoal != null) {
                if (mc.level.getBlockState(me.blockPosition()).is(Blocks.NETHER_PORTAL)
                        || mc.level.getBlockState(me.blockPosition()).is(Blocks.END_PORTAL)) {
                    MoveControl.stop(); // inside the portal: hold still and let it take us
                } else if (!MoveControl.isActive()) {
                    ScreenOps.startMove(mc, me, portalGoal.getX() + 0.5, portalGoal.getY(),
                            portalGoal.getZ() + 0.5, 0.3, "follow portal");
                }
                if (++lostTicks > PORTAL_GRACE_TICKS) {
                    ctx.report(ReportClass.STATUS, "follow: portal transit failed; giving up");
                    MoveControl.stop();
                    return StepResult.FAILED;
                }
                return StepResult.RUNNING;
            }
            // Freshly lost: was the target last seen next to a portal?
            if (everSeen && lostTicks == 0) {
                BlockPos p = findPortalNear(mc, seenX, seenY, seenZ);
                if (p != null) {
                    portalGoal = p;
                    endTransit = mc.level.getBlockState(p).is(Blocks.END_PORTAL);
                    ctx.report(ReportClass.STATUS, "follow: " + who + " entered a "
                            + (endTransit ? "END" : "nether") + " portal -- following through");
                    ScreenOps.startMove(mc, me, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.3, "follow portal");
                }
            }
            if (++lostTicks > LOST_GRACE_TICKS && portalGoal == null) {
                ctx.report(ReportClass.STATUS, "follow: '" + who + "' not visible; giving up");
                MoveControl.stop();
                return StepResult.FAILED;
            }
            return StepResult.RUNNING;
        }
        lostTicks = 0;
        portalGoal = null;
        endTransit = false;
        seenX = target.getX(); seenY = target.getY(); seenZ = target.getZ();
        everSeen = true;
        if (!announced) {
            announced = true;
            ctx.report(ReportClass.STATUS, "follow: tracking " + target.getName().getString()
                    + " (standoff " + (int) dist + "m)");
        }

        double gap = Math.sqrt(me.distanceToSqr(target));

        // Sprint catch-up (Kishku7 2026-07-01): kick on when falling behind >2x the standoff,
        // back to walking once inside 1.5x. Vanilla blocks sprint at food <= 6 on its own.
        if (gap > dist * SPRINT_ON_FACTOR) {
            MoveControl.setSprint(true);
        } else if (gap <= dist * SPRINT_OFF_FACTOR) {
            MoveControl.setSprint(false);
        }

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

    /**
     * Nearest nether/end portal block within {@link #PORTAL_SCAN_RADIUS} of the given position.
     * For an end portal (a 3x3 pool) the CENTROID of the pool is returned so we walk onto its
     * center; a nether portal's nearest block is fine (any column of the sheet works).
     */
    private static BlockPos findPortalNear(Minecraft mc, double x, double y, double z) {
        BlockPos c = BlockPos.containing(x, y, z);
        BlockPos nearestNether = null;
        double nd = Double.MAX_VALUE;
        long ex = 0, ey = 0, ez = 0;
        int endCount = 0;
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-PORTAL_SCAN_RADIUS, -2, -PORTAL_SCAN_RADIUS),
                c.offset(PORTAL_SCAN_RADIUS, 3, PORTAL_SCAN_RADIUS))) {
            if (mc.level.getBlockState(bp).is(Blocks.NETHER_PORTAL)) {
                double d = bp.distSqr(c);
                if (d < nd) { nd = d; nearestNether = bp.immutable(); }
            } else if (mc.level.getBlockState(bp).is(Blocks.END_PORTAL)) {
                ex += bp.getX(); ey += bp.getY(); ez += bp.getZ(); endCount++;
            }
        }
        if (endCount > 0) {
            return new BlockPos((int) (ex / endCount), (int) (ey / endCount), (int) (ez / endCount));
        }
        return nearestNether;
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        MoveControl.stop();
    }
}
