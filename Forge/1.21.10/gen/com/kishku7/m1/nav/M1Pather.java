package com.kishku7.m1.nav;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * M1's OWN A* pather (Master decision, 2026-07-02) -- player-semantics node rules
 * (PlayerNodeEvaluator), a bounded 16-block receding horizon per segment, and a best-progress
 * PARTIAL result when the goal lies beyond it (the 16+2 rule: we never search farther than the
 * horizon, but the destination can be anywhere in the world). The structure follows the classic
 * heap A* the vanilla pather uses; the code is original -- no verbatim Mojang source.
 */
public final class M1Pather {

    /** Max horizontal block distance per segment (Master: 16, +2 arrival slack). */
    public static final int HORIZON = 16;

    private static final int MAX_EXPANSIONS = 2048;

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private M1Pather() {}

    /**
     * Compute one segment from the start feet-cell toward (gx,gy,gz). Never returns null on a
     * live level; a zero-length path means "boxed in" (blocked). partial=true means the goal is
     * beyond this segment and the caller should roll the next segment on arrival at its end.
     */
    public static NavPath compute(Level lvl, BlockPos start, double gx, double gy, double gz) {
        if (lvl == null || start == null) {
            return null;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Map<Long, NavNode> nodes = new HashMap<>();
        NavHeap open = new NavHeap();

        NavNode s = new NavNode(start.getX(), start.getY(), start.getZ());
        s.g = 0;
        s.h = h(s.x, s.y, s.z, gx, gy, gz);
        nodes.put(s.key, s);
        open.push(s);

        NavNode best = null;
        NavNode goalHit = null;
        int expansions = 0;

        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            NavNode n = open.pop();
            n.closed = true;
            expansions++;

            if (reached(n, gx, gy, gz)) {
                goalHit = n;
                break;
            }
            if (n != s && (best == null || n.h < best.h - 1e-9
                    || (Math.abs(n.h - best.h) < 1e-9 && n.g < best.g))) {
                best = n;
            }

            for (int[] d : DIRS) {
                int nx = n.x + d[0];
                int nz = n.z + d[1];
                long hx = nx - start.getX();
                long hz = nz - start.getZ();
                if (hx * hx + hz * hz > (long) HORIZON * HORIZON) {
                    continue; // receding horizon: never expand beyond it
                }
                long res = PlayerNodeEvaluator.resolve(lvl, m, n.x, n.y, n.z, d[0], d[1]);
                if (res == PlayerNodeEvaluator.NONE) {
                    continue;
                }
                int ny = PlayerNodeEvaluator.resY(res);
                byte fl = PlayerNodeEvaluator.resFlags(res);

                double base = (d[0] != 0 && d[1] != 0) ? 1.4142 : 1.0;
                double c = base;
                if ((fl & NavPath.F_WATER) != 0) {
                    c = base * 3.0;
                }
                if ((fl & NavPath.F_JUMP) != 0) {
                    c += 0.5;
                }
                if ((fl & NavPath.F_OPEN) != 0) {
                    c += 2.0;
                }
                if ((fl & NavPath.F_DROP) != 0) {
                    c += 1.0;
                }
                if (ny > n.y) {
                    c += 0.3; // mild uphill preference for flat routes
                }
                double ng = n.g + c;

                long key = NavNode.key(nx, ny, nz);
                NavNode t = nodes.get(key);
                if (t == null) {
                    t = new NavNode(nx, ny, nz);
                    t.h = h(nx, ny, nz, gx, gy, gz);
                    nodes.put(key, t);
                } else if (t.closed || ng >= t.g) {
                    continue;
                }
                t.g = ng;
                t.parent = n;
                t.flags = fl;
                if (t.heapIdx < 0) {
                    open.push(t);
                } else {
                    open.update(t);
                }
            }
        }

        NavNode end = goalHit != null ? goalHit : best;
        if (end == null || end == s) {
            return new NavPath(new int[0][], new byte[0], goalHit == null,
                    Math.hypot(s.x + 0.5 - gx, s.z + 0.5 - gz));
        }
        int len = 0;
        for (NavNode t = end; t != s; t = t.parent) {
            len++;
        }
        int[][] pts = new int[len][];
        byte[] flags = new byte[len];
        int i = len - 1;
        for (NavNode t = end; t != s; t = t.parent, i--) {
            pts[i] = new int[]{t.x, t.y, t.z};
            flags[i] = t.flags;
        }
        return new NavPath(pts, flags, goalHit == null,
                Math.hypot(end.x + 0.5 - gx, end.z + 0.5 - gz));
    }

    private static boolean reached(NavNode n, double gx, double gy, double gz) {
        double dx = n.x + 0.5 - gx;
        double dz = n.z + 0.5 - gz;
        return dx * dx + dz * dz <= 1.0 && Math.abs(n.y - gy) <= 1.5;
    }

    private static double h(int x, int y, int z, double gx, double gy, double gz) {
        double dx = x + 0.5 - gx;
        double dy = (y - gy) * 0.8;
        double dz = z + 0.5 - gz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
