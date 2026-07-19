package com.kishku7.m1.nav;

/** A* search node: one standable cell (feet position) plus search bookkeeping. */
final class NavNode {

    final int x;
    final int y;
    final int z;
    final long key;

    double g = Double.MAX_VALUE;
    double h;
    NavNode parent;
    byte flags;
    boolean closed;
    int heapIdx = -1;

    NavNode(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.key = key(x, y, z);
    }

    double f() {
        return g + h;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
