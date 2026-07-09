package com.kishku7.m1.nav;

/**
 * Capability flags that parameterize traversability. Conservative defaults; the AI (or Master)
 * flips these when gear makes an edge legal (lava-walking boots, fire resistance, a lava boat).
 * Static for now -- a "nav caps" command can expose them later.
 */
public final class NavCaps {

    /** Lava surface is standable (lava-walking boots equipped). */
    public static volatile boolean lavaWalk = false;

    /** Fire / magma damage is acceptable (fire resistance active). */
    public static volatile boolean fireRes = false;

    /** Maximum blocks of fall the pather may plan (fall-damage tolerance). */
    public static volatile int maxDrop = 3;

    /** Maximum wade/swim depth in blocks before water is a crossing (boat territory, later). */
    public static volatile int maxWadeDepth = 2;

    private NavCaps() {}
}
