package com.kishku7.m1;

import java.util.Locale;

import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.nav.M1Pather;
import com.kishku7.m1.nav.NavPath;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Executor for the OWN pather (M1Pather) -- Master decision 2026-07-02. Drives the player along
 * segmented receding-horizon paths (16+2), with player-semantics execution:
 *
 *  - PURE-PURSUIT steering: yaw aims at a lookahead point along the path, recomputed every
 *    STEER_PERIOD (4) ticks and rate-capped per tick -- kills the 1-tick look jitter.
 *  - STAIRS/SLABS are WALKED (jump only on F_JUMP full-block rises + a collision-recovery jump).
 *  - DOORS/GATES: an upcoming F_OPEN waypoint pauses movement, faces the door, right-clicks it
 *    open (Crafting.place -- the proven use path), verifies the OPEN state, then resumes. Follow
 *    and goto open doors themselves now (Master's session-702 feedback).
 *  - SEGMENTS: on a partial path, the next segment is computed when within SEG_TRIGGER (2) blocks
 *    of the segment end. Never computes farther than the horizon; destination can be anywhere.
 *  - TELEMETRY: segment/door/blocked/arrived events emit as agent STATUS reports on the socket
 *    (session-702's rail+stairs failures were invisible on the wire; nav failures no longer are).
 *
 * Reached through the MoveControl facade -- all action-layer call sites are unchanged.
 */
public final class NavEngine {

    private static final int STEER_PERIOD = 4;      // steering cadence in ticks (Master: 4)
    private static final float MAX_YAW_STEP = 25f;  // deg/tick rotation cap
    private static final float MAX_PITCH_STEP = 12f;
    private static final double LOOKAHEAD = 2.5;    // pure-pursuit aim distance along the path
    private static final double WP_REACH = 0.7;
    private static final double SEG_TRIGGER = 2.0;  // roll the next segment this close to seg end
    private static final int MAX_SEGMENTS = 512;
    private static final int MAX_NO_PROGRESS = 3;   // anti-local-minimum strikes
    private static final int MAX_DOOR_TRIES = 5;

    private static volatile boolean active = false;
    private static volatile String status = "idle";
    private static double goalX;
    private static double goalY;
    private static double goalZ;
    private static double stopDist;
    private static int ticks;
    private static int maxTicks;
    private static NavPath path;
    private static int idx;
    private static int segment;
    private static int finalRecomputes;
    private static double prevEndDist;
    private static int noProgress;
    private static boolean sprint;
    private static float desiredYaw;
    private static boolean haveDesired;
    private static double stallX = Double.NaN;
    private static double stallZ = Double.NaN;
    private static int doorIdx = -1;
    private static int doorTries;
    private static int lastJumpTick;
    private static boolean directFinish;
    private static int directTicks;

    private NavEngine() {}

    public static synchronized boolean start(Minecraft mc, double tx, double ty, double tz,
            double stop, int maxT) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return false;
        }
        goalX = tx;
        goalY = ty;
        goalZ = tz;
        stopDist = stop;
        maxTicks = maxT;
        ticks = 0;
        segment = 0;
        finalRecomputes = 0;
        prevEndDist = Double.MAX_VALUE;
        noProgress = 0;
        doorIdx = -1;
        doorTries = 0;
        haveDesired = false;
        directFinish = false;
        directTicks = 0;
        stallX = Double.NaN;
        stallZ = Double.NaN;
        if (!nextSegment(mc, p)) {
            status = "no path";
            active = false;
            return false;
        }
        active = true;
        status = "moving";
        MoveControl.navStatus("moving");
        report("nav: -> (" + fmt(tx) + "," + fmt(ty) + "," + fmt(tz) + ") dist "
                + fmt(Math.hypot(tx - p.getX(), tz - p.getZ()))
                + (path.partial ? " (segmented)" : ""));
        return true;
    }

    public static synchronized void stop() {
        if (!active) {
            return;
        }
        active = false;
        status = "idle";
        sprint = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            release(mc);
        }
    }

    public static synchronized void setSprint(boolean s) {
        sprint = s;
    }

    public static boolean isActive() {
        return active;
    }

    public static String status() {
        return status;
    }

    public static synchronized double targetX() {
        return goalX;
    }

    public static synchronized double targetZ() {
        return goalZ;
    }

    public static synchronized int waypointIdx() {
        return idx;
    }

    public static synchronized int waypointCount() {
        return path == null ? 0 : path.length();
    }

    public static synchronized int segmentNo() {
        return segment;
    }

    /** Ticked from MoveControl.tick (END_CLIENT_TICK) while active. */
    static synchronized void tick(Minecraft mc) {
        if (!active) {
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            active = false;
            status = "idle";
            return;
        }
        if (M1Compat.screen(mc) != null) {
            release(mc); // menu open: hold position, keep the goal
            return;
        }
        ticks++;
        if (ticks > maxTicks) {
            report("nav: timeout at (" + fmt(p.getX()) + "," + fmt(p.getZ()) + ")");
            halt(mc, "timeout");
            return;
        }

        double px = p.getX();
        double py = p.getY();
        double pz = p.getZ();

        if (Math.hypot(goalX - px, goalZ - pz) <= stopDist) {
            report("nav: arrived (" + segment + " segment" + (segment == 1 ? "" : "s")
                    + ", " + ticks + "t)");
            halt(mc, "arrived");
            return;
        }

        advance(px, py, pz);

        boolean roll = idx >= path.length();
        if (!roll && path.partial && idx >= path.length() - 1) {
            int[] last = path.pts[path.length() - 1];
            if (Math.hypot(last[0] + 0.5 - px, last[2] + 0.5 - pz) < SEG_TRIGGER) {
                roll = true; // 16+2: compute the next segment just before the seam
            }
        }
        if (roll && !directFinish) {
            if (!path.partial && Math.hypot(goalX - px, goalZ - pz) < 3.0) {
                directFinish = true; // node resolution exhausted: close the last stretch directly
                report("nav: final approach");
            } else {
                if (!path.partial && ++finalRecomputes > 2) {
                    report("nav: goal unreachable (final segment exhausted)");
                    halt(mc, "blocked");
                    return;
                }
                if (!nextSegment(mc, p)) {
                    halt(mc, "blocked");
                    return;
                }
                if (!directFinish) {
                advance(px, py, pz);
                if (idx >= path.length()) {
                    if (Math.hypot(goalX - px, goalZ - pz) < 3.0) {
                        directFinish = true;
                        report("nav: final approach");
                    } else {
                        report("nav: blocked (empty segment)");
                        halt(mc, "blocked");
                        return;
                    }
                }
                }
            }
        }

        if (directFinish) {
            if (++directTicks > 60) {
                report("nav: blocked (cannot close final approach)");
                halt(mc, "blocked");
                return;
            }
            float want = (float) Math.toDegrees(Math.atan2(-(goalX - px), goalZ - pz));
            p.setYRot(step(p.getYRot(), want, MAX_YAW_STEP));
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(false);
            mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
            return;
        }

        // Door/gate splice: an upcoming F_OPEN waypoint within reach pauses travel.
        int d = upcomingDoor();
        if (d >= 0) {
            int[] c = path.pts[d];
            double cx = c[0] + 0.5;
            double cy = c[1] + 0.5;
            double cz = c[2] + 0.5;
            double ex = cx - px;
            double ey = cy - (py + p.getEyeHeight());
            double ez = cz - pz;
            if (ex * ex + ey * ey + ez * ez < 9.0 && openDoor(mc, p, d, cx, cy, cz)) {
                return; // busy with the door this tick
            }
        }

        // Pure-pursuit steering on a 4-tick cadence, rate-capped (kills the 1-tick jitter).
        if (!haveDesired || (ticks % STEER_PERIOD) == 0) {
            double[] aim = lookahead(px, pz);
            desiredYaw = (float) Math.toDegrees(Math.atan2(-(aim[0] - px), aim[1] - pz));
            haveDesired = true;
        }
        p.setYRot(step(p.getYRot(), desiredYaw, MAX_YAW_STEP));
        if (Math.abs(p.getXRot()) > 1f) {
            p.setXRot(step(p.getXRot(), 0f, MAX_PITCH_STEP)); // level the view while traveling
        }

        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(sprint);

        int wi = Math.min(idx, path.length() - 1);
        int[] cur = path.pts[wi];
        byte fl = path.flags[wi];
        double dyNode = cur[1] - py;
        boolean inWater = p.isInWater();
        boolean jump = ((fl & NavPath.F_JUMP) != 0 && dyNode > 0.25 && p.onGround())
                || (inWater && dyNode > -0.1)
                || (p.horizontalCollision && p.onGround() && ticks - lastJumpTick > 8 && doorIdx < 0);
        if (jump && p.onGround()) {
            lastJumpTick = ticks;
        }
        mc.options.keyJump.setDown(jump);

        // Stall guard: no ground covered in a second -> re-path; progress guard bounds futility.
        if ((ticks % 20) == 0) {
            if (!Double.isNaN(stallX) && Math.hypot(px - stallX, pz - stallZ) < 0.5) {
                report("nav: stalled at (" + fmt(px) + "," + fmt(pz) + "), re-pathing");
                if (!nextSegment(mc, p)) {
                    halt(mc, "blocked");
                    return;
                }
            }
            stallX = px;
            stallZ = pz;
        }
    }

    private static boolean nextSegment(Minecraft mc, LocalPlayer p) {
        if (segment >= MAX_SEGMENTS) {
            report("nav: segment cap reached");
            return false;
        }
        NavPath np = M1Pather.compute(mc.level, p.blockPosition(), goalX, goalY, goalZ);
        if (np == null) {
            report("nav: no path (no level)");
            return false;
        }
        if (np.length() == 0) {
            if (!np.partial) {
                // Goal satisfied at the START node (node resolution coarser than stopDist):
                // do not call this boxed in -- close the last stretch directly. (702b fix)
                path = np;
                idx = 0;
                segment++;
                directFinish = true;
                directTicks = 0;
                status = "moving";
                MoveControl.navStatus("moving");
                report("nav: final approach (at goal node)");
                return true;
            }
            String blk = String.valueOf(mc.level.getBlockState(p.blockPosition()).getBlock());
            report("nav: no path from (" + fmt(p.getX()) + "," + fmt(p.getY()) + "," + fmt(p.getZ())
                    + ") -- boxed in (standing in " + blk + ")");
            return false;
        }
        if (np.partial) {
            if (np.endGoalDist > prevEndDist - 0.5) {
                noProgress++;
                if (noProgress >= MAX_NO_PROGRESS) {
                    report("nav: no progress over " + noProgress
                            + " segments (local minimum) -- blocked");
                    return false;
                }
            } else {
                noProgress = 0;
                prevEndDist = np.endGoalDist;
            }
        }
        path = np;
        idx = 0;
        segment++;
        haveDesired = false;
        doorIdx = -1;
        doorTries = 0;
        stallX = Double.NaN;
        stallZ = Double.NaN;
        status = "moving";
        MoveControl.navStatus("moving");
        report("nav: segment " + segment + " -- " + np.length() + " wp"
                + (np.partial ? ", " + fmt(np.endGoalDist) + " to go" : ", final"));
        return true;
    }

    /** Advance to the farthest reached waypoint (window of 4; never skips an unopened door). */
    private static void advance(double px, double py, double pz) {
        if (path == null) {
            return;
        }
        int lim = Math.min(path.length(), idx + 4);
        for (int i = idx; i < lim; i++) {
            if ((path.flags[i] & NavPath.F_OPEN) != 0) {
                break;
            }
            int[] c = path.pts[i];
            if (Math.hypot(c[0] + 0.5 - px, c[2] + 0.5 - pz) < WP_REACH
                    && Math.abs(c[1] - py) < 0.7) { // tight vertically: never eat a climb standing still (702b fix)
                idx = i + 1;
            }
        }
    }

    /** Aim point ~LOOKAHEAD blocks along the remaining path; never cuts a jump/door corner. */
    private static double[] lookahead(double px, double pz) {
        double acc = 0;
        double lx = px;
        double lz = pz;
        for (int i = idx; i < path.length(); i++) {
            double wx = path.pts[i][0] + 0.5;
            double wz = path.pts[i][2] + 0.5;
            acc += Math.hypot(wx - lx, wz - lz);
            lx = wx;
            lz = wz;
            if (acc >= LOOKAHEAD || (path.flags[i] & (NavPath.F_OPEN | NavPath.F_JUMP)) != 0) {
                return new double[]{wx, wz};
            }
        }
        return new double[]{lx, lz};
    }

    private static int upcomingDoor() {
        if (path == null) {
            return -1;
        }
        int lim = Math.min(path.length(), idx + 3);
        for (int i = idx; i < lim; i++) {
            if ((path.flags[i] & NavPath.F_OPEN) != 0) {
                return i;
            }
        }
        return -1;
    }

    /** Returns true while busy opening (movement paused this tick). */
    private static boolean openDoor(Minecraft mc, LocalPlayer p, int d, double cx, double cy, double cz) {
        int[] c = path.pts[d];
        BlockPos bp = new BlockPos(c[0], c[1], c[2]);
        BlockState s = mc.level.getBlockState(bp);
        if (!s.hasProperty(BlockStateProperties.OPEN)) {
            BlockPos up = bp.above();
            BlockState su = mc.level.getBlockState(up);
            if (su.hasProperty(BlockStateProperties.OPEN)) {
                bp = up;
                s = su;
                cy = cy + 1.0;
            } else {
                path.flags[d] &= ~NavPath.F_OPEN; // no door here anymore; resume
                doorIdx = -1;
                return false;
            }
        }
        if (s.getValue(BlockStateProperties.OPEN)) {
            path.flags[d] &= ~NavPath.F_OPEN;
            doorIdx = -1;
            doorTries = 0;
            report("nav: opened " + s.getBlock() + " at " + bp.toShortString());
            return false;
        }
        release(mc); // hold still while working the door
        if (doorIdx != d) {
            doorIdx = d;
            doorTries = 0;
        }
        boolean aimed = aimAt(p, cx, cy, cz);
        if (aimed && (ticks % STEER_PERIOD) == 0) {
            doorTries++;
            if (doorTries > MAX_DOOR_TRIES) {
                report("nav: cannot open " + s.getBlock() + " at " + bp.toShortString()
                        + " -- blocked");
                halt(mc, "blocked");
                return true;
            }
            Crafting.place(mc); // proven right-click-use path; crosshair is on the door
        }
        return true;
    }

    /** Rate-capped aim at a point; true when within 8 degrees on both axes. */
    private static boolean aimAt(LocalPlayer p, double tx, double ty, double tz) {
        double dx = tx - p.getX();
        double dz = tz - p.getZ();
        double dy = ty - (p.getY() + p.getEyeHeight());
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float ny = step(p.getYRot(), wantYaw, MAX_YAW_STEP);
        float np = step(p.getXRot(), wantPitch, MAX_PITCH_STEP);
        p.setYRot(ny);
        p.setXRot(np);
        return Math.abs(Mth.wrapDegrees(wantYaw - ny)) < 8f && Math.abs(wantPitch - np) < 8f;
    }

    private static float step(float cur, float want, float max) {
        float delta = Mth.wrapDegrees(want - cur);
        if (delta > max) {
            delta = max;
        }
        if (delta < -max) {
            delta = -max;
        }
        return cur + delta;
    }

    private static void halt(Minecraft mc, String st) {
        active = false;
        status = st;
        MoveControl.navStatus(st);
        sprint = false;
        release(mc);
    }

    private static void release(Minecraft mc) {
        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keySprint.setDown(false);
        }
    }

    private static String lastReport = "";
    private static int repeatCount;

    private static void report(String t) {
        // Dedupe identical consecutive lines (702b flood fix): emit 1st, then x5/x25/x100...
        if (t.equals(lastReport)) {
            repeatCount++;
            if (repeatCount != 5 && repeatCount != 25 && (repeatCount % 100) != 0) {
                return;
            }
            t = t + " (x" + repeatCount + ")";
        } else {
            lastReport = t;
            repeatCount = 1;
        }
        try {
            AgentRuntime.reports().emit(ReportClass.STATUS, t, 0L);
        } catch (Throwable ignored) {
            // report channel down: never let telemetry break navigation
        }
        M1Server.log(t);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
