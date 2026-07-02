package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Movement FACADE + the vanilla-path fallback executor.
 *
 * The OWN pather ({@link NavEngine} over {@link com.kishku7.m1.nav.M1Pather}) is the DEFAULT
 * engine (Master decision, 2026-07-02 -- player semantics, 16+2 segments, smooth steering).
 * MoveControl stays the single surface the action layer talks to (start/stop/status/sprint/
 * waypoints) and delegates to NavEngine while it is active. The vanilla engine below (PathOracle
 * path + per-tick follower) is retained as the fallback mover -- "nav vanilla" switches back.
 *
 * Vanilla engine: follows a vanilla-computed Path waypoint by waypoint, holding the forward
 * keybind and steering yaw, autojumping for steps and on collision. On a partial path it walks
 * to the path's end then recomputes (legs). If progress stalls it recomputes; failing that it
 * stops and reports "blocked".
 */
public final class MoveControl {

    private static final int MAX_RECOMPUTES = 8;

    /** OWN pather is the default engine (Master, 2026-07-02). "nav vanilla" flips this off. */
    private static volatile boolean ownNav = true;

    private static volatile boolean active = false;
    private static double[][] wp = new double[0][];
    private static int idx;
    private static double finalX, finalZ, stopDist = 1.0;
    private static int ticks, maxTicks, recomputes;
    private static double stallX = Double.NaN, stallZ = Double.NaN;
    private static volatile String status = "idle";
    private static volatile boolean sprint = false;

    private MoveControl() {}

    public static boolean ownNav() { return ownNav; }
    public static void setOwnNav(boolean b) { ownNav = b; }

    /** NavEngine pushes its lifecycle status here so the facade reports one truth. */
    static void navStatus(String s) { status = s; }

    public static synchronized boolean startPath(Path path, double fx, double fz, double stop, int maxT) {
        NavEngine.stop(); // one engine at a time
        double[][] pts = toPoints(path);
        if (pts.length == 0) { status = "no path"; active = false; return false; }
        wp = pts; idx = 0; finalX = fx; finalZ = fz; stopDist = stop;
        maxTicks = maxT; ticks = 0; recomputes = 0;
        stallX = Double.NaN; stallZ = Double.NaN;
        active = true; status = "moving";
        return true;
    }

    private static double[][] toPoints(Path path) {
        if (path == null) return new double[0][];
        int n = path.getNodeCount();
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            BlockPos bp = path.getNodePos(i);
            a[i] = new double[]{ bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5 };
        }
        return a;
    }

    public static synchronized void stop() {
        NavEngine.stop();
        active = false; status = "idle"; sprint = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) release(mc); // release forward/jump so the player does not keep walking after a stop
    }

    /** Caller-requested sprint (e.g. follow catch-up). Applied while a path is active; vanilla
     *  itself refuses to sprint at food &lt;= 6, so no hunger check is duplicated here. */
    public static synchronized void setSprint(boolean s) { sprint = s; NavEngine.setSprint(s); }
    public static synchronized boolean isSprinting() { return sprint; }
    public static boolean isActive() { return active || NavEngine.isActive(); }
    public static String status() { return status; }
    public static synchronized double targetX() { return NavEngine.isActive() ? NavEngine.targetX() : finalX; }
    public static synchronized double targetZ() { return NavEngine.isActive() ? NavEngine.targetZ() : finalZ; }
    public static synchronized int waypointIdx() { return NavEngine.isActive() ? NavEngine.waypointIdx() : idx; }
    public static synchronized int waypointCount() { return NavEngine.isActive() ? NavEngine.waypointCount() : wp.length; }

    /** Registered on ClientTickEvents.END_CLIENT_TICK. */
    public static synchronized void tick(Minecraft mc) {
        if (NavEngine.isActive()) { NavEngine.tick(mc); return; }
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { active = false; release(mc); return; }
        if (M1Compat.screen(mc) != null) { release(mc); return; } // paused/menu: hold position, keep goal
        ticks++;
        if (ticks > maxTicks) { status = "timeout"; active = false; release(mc); return; }

        double px = p.getX(), pz = p.getZ(), py = p.getY();

        if (idx >= wp.length) { finishOrRecompute(mc, p); return; }
        double[] cur = wp[idx];
        double dx = cur[0] - px, dz = cur[2] - pz;
        double hd = Math.sqrt(dx * dx + dz * dz);
        double dyNode = cur[1] - py;

        if (hd < 0.7 && Math.abs(dyNode) < 1.6) {
            idx++;
            if (idx >= wp.length) { finishOrRecompute(mc, p); return; }
            cur = wp[idx];
            dx = cur[0] - px; dz = cur[2] - pz; dyNode = cur[1] - py;
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.setYRot(yaw);
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(sprint);
        boolean needUp = dyNode > 0.25;
        mc.options.keyJump.setDown((needUp && p.onGround()) || (p.horizontalCollision && p.onGround()));

        if ((ticks % 20) == 0) {
            if (!Double.isNaN(stallX)) {
                double moved = Math.hypot(px - stallX, pz - stallZ);
                if (moved < 0.5 && !recompute(mc, p)) {
                    status = "blocked"; active = false; release(mc); return;
                }
            }
            stallX = px; stallZ = pz;
        }
    }

    private static void finishOrRecompute(Minecraft mc, LocalPlayer p) {
        double hdFinal = Math.hypot(finalX - p.getX(), finalZ - p.getZ());
        if (hdFinal <= stopDist) { status = "arrived"; active = false; release(mc); return; }
        if (recompute(mc, p)) return;     // partial path consumed; keep going on a fresh leg
        status = "blocked"; active = false; release(mc);
    }

    private static boolean recompute(Minecraft mc, LocalPlayer p) {
        if (recomputes >= MAX_RECOMPUTES) return false;
        recomputes++;
        Path np = PathOracle.compute(mc, finalX, p.getY(), finalZ, 1);
        double[][] pts = toPoints(np);
        if (pts.length == 0) return false;
        wp = pts; idx = 0; stallX = Double.NaN; stallZ = Double.NaN; status = "moving";
        return true;
    }

    private static void release(Minecraft mc) {
        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keySprint.setDown(false);
        }
    }
}
