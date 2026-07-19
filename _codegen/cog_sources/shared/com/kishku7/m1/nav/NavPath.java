package com.kishku7.m1.nav;

/**
 * Result of one segment computation: waypoints (feet cells, start-exclusive) plus per-waypoint
 * flags. A PARTIAL path means the goal lies beyond this segment's horizon and another segment
 * follows (the 16+2 receding-horizon rule -- Master, 2026-07-02).
 */
public final class NavPath {

    /** Reaching this waypoint requires a jump (full-block rise; stairs/slabs are walked). */
    public static final byte F_JUMP = 1;
    /** This cell holds a closed door/fence gate the executor must open before passing. */
    public static final byte F_OPEN = 2;
    /** This cell is water (wade/swim; deep water is rejected by the evaluator). */
    public static final byte F_WATER = 4;
    /** Reaching this waypoint is a fall of 2+ blocks. */
    public static final byte F_DROP = 8;

    public final int[][] pts;
    public final byte[] flags;
    public final boolean partial;
    public final double endGoalDist;

    NavPath(int[][] pts, byte[] flags, boolean partial, double endGoalDist) {
        this.pts = pts;
        this.flags = flags;
        this.partial = partial;
        this.endGoalDist = endGoalDist;
    }

    public int length() {
        return pts.length;
    }
}
