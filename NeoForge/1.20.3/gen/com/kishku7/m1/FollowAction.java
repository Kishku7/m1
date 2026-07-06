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
 * its own -- runs until interrupted / replaced ({@code stop}).
 *
 * RESOLVE-OR-HALT (Master, 2026-07-02, after the 702b roof thrash): the follower measures the gap
 * HORIZONTALLY. If it is horizontally at the standoff but the target is far above/below, it makes
 * ONE deliberate attempt to resolve a path to the target's exact position; if that fails (or ends
 * without closing the height), it HOLDS -- stops moving, reports once, and waits until the target
 * moves somewhere new before trying again. Same hold rule when path resolution fails outright or
 * the mover reports blocked repeatedly. No re-path thrashing, no telemetry floods.
 */
public final class FollowAction implements MinecraftAction {

    private static final double FOLLOW_HYST = 1.5;     // deadband beyond standoff before we chase again
    private static final double REACQUIRE_DRIFT = 1.5; // re-path once the standoff point moves this far
    private static final int LOST_GRACE_TICKS = 100;   // ~5s out-of-sight grace before giving up
    private static final int REPATH_MIN_TICKS = 10;    // never re-path more often than this while chasing

    private static final double SPRINT_ON_FACTOR = 2.0;  // sprint when gap > 2x standoff (Master 2026-07-01)
    private static final double SPRINT_OFF_FACTOR = 1.5; // stop sprinting once back inside 1.5x
    private static final double VERTICAL_SLACK = 2.0;    // height difference we treat as "with you"
    private static final int BLOCKED_STRIKES = 3;        // mover blocked this often -> hold
    private static final int PORTAL_SCAN_RADIUS = 4;     // blocks around last-seen pos to find a portal
    private static final int PORTAL_GRACE_TICKS = 400;   // ~20s to transit + re-acquire on the other side

    private final String who;
    private final double dist;
    private int lostTicks;
    private boolean announced;

    // Resolve-or-halt state
    private double holdX = Double.NaN, holdY, holdZ;   // target pos when we entered hold (NaN = not holding)
    private boolean triedVertical;                     // one climb attempt per vertical episode
    private int repathCooldown;
    private int blockedStrikes;

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
                // CRITICAL (Master 2026-07-01): after an END portal transit, stand absolutely still.
                ctx.report(ReportClass.STATUS, "follow: passed through END portal -- AUTO-STOP (holding position)");
                return StepResult.DONE;
            }
            ctx.report(ReportClass.STATUS, "follow: arrived in " + M1Compat.keyId(dim) + "; re-acquiring " + who);
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
        if (repathCooldown > 0) repathCooldown--;

        // Holding after a failed resolve: wait until the target MOVES before trying anything again.
        if (!Double.isNaN(holdX)) {
            if (Math.hypot(target.getX() - holdX, target.getZ() - holdZ) > 2.0
                    || Math.abs(target.getY() - holdY) > VERTICAL_SLACK) {
                holdX = Double.NaN; // target moved; resume normal behavior
                triedVertical = false;
                blockedStrikes = 0;
            } else {
                if (MoveControl.isActive()) MoveControl.stop();
                return StepResult.RUNNING;
            }
        }

        double hGap = Math.hypot(me.getX() - target.getX(), me.getZ() - target.getZ());
        double vGap = target.getY() - me.getY();

        // Sprint catch-up: kick on when falling behind >2x the standoff (horizontal), off inside 1.5x.
        if (hGap > dist * SPRINT_ON_FACTOR) {
            MoveControl.setSprint(true);
        } else if (hGap <= dist * SPRINT_OFF_FACTOR) {
            MoveControl.setSprint(false);
        }

        // Horizontally with the target (inside standoff, or inside the deadband while stopped)?
        if (hGap <= dist || (hGap <= dist + FOLLOW_HYST && !MoveControl.isActive())) {
            if (Math.abs(vGap) > VERTICAL_SLACK) {
                return verticalResolve(ctx, mc, me, target, vGap);
            }
            triedVertical = false;
            if (hGap <= dist && MoveControl.isActive()) MoveControl.stop();
            return StepResult.RUNNING;
        }
        triedVertical = false;

        // Mover reported blocked while we were chasing? Count strikes; hold after too many.
        if (!MoveControl.isActive() && "blocked".equals(MoveControl.status())) {
            if (++blockedStrikes >= BLOCKED_STRIKES) {
                enterHold(ctx, target, vGap);
                return StepResult.RUNNING;
            }
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
        if (needRepath && repathCooldown == 0) {
            repathCooldown = REPATH_MIN_TICKS;
            String r = ScreenOps.startMove(mc, me, sx, target.getY(), sz, 1.0, "follow " + who);
            if (!r.startsWith("OK")) {
                enterHold(ctx, target, vGap);
            }
        }
        return StepResult.RUNNING;
    }

    /** Horizontally there but the target is far above/below: ONE resolve attempt, else hold. */
    private StepResult verticalResolve(ActionContext ctx, Minecraft mc, LocalPlayer me,
            Player target, double vGap) {
        if (!triedVertical) {
            triedVertical = true;
            String r = ScreenOps.startMove(mc, me, target.getX(), target.getY(), target.getZ(),
                    1.0, "follow climb");
            if (!r.startsWith("OK")) {
                enterHold(ctx, target, vGap);
            }
            return StepResult.RUNNING;
        }
        if (!MoveControl.isActive()) {
            // The climb attempt ended. Did it actually close the height?
            if (Math.abs(target.getY() - me.getY()) > VERTICAL_SLACK) {
                enterHold(ctx, target, vGap);
            } else {
                triedVertical = false;
            }
        }
        return StepResult.RUNNING;
    }

    private void enterHold(ActionContext ctx, Player target, double vGap) {
        holdX = target.getX();
        holdY = target.getY();
        holdZ = target.getZ();
        MoveControl.stop();
        String where = Math.abs(vGap) > VERTICAL_SLACK
                ? String.format(java.util.Locale.ROOT, "%.0fm %s me", Math.abs(vGap), vGap > 0 ? "above" : "below")
                : "somewhere I cannot path to";
        ctx.report(ReportClass.ADVISORY, "follow: cannot resolve a path to you (" + where
                + "); holding position until you move");
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
