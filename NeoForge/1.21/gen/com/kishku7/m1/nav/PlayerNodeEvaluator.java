package com.kishku7.m1.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * PLAYER-semantics cell rules -- the de-mobbed replacement for the vanilla WalkNodeEvaluator
 * (which encodes MOB semantics: closed fence gates are FENCE=blocked, rails are avoided, +1Y is
 * always a jump). The session-702 failures (gate, rail, stairs) all trace to that mismatch.
 *
 * Player rules here:
 *  - closed wooden doors / fence gates are PASSABLE with an open cost (F_OPEN; the executor
 *    right-clicks them open in-line). Iron doors stay blocked (no hand-open).
 *  - rails, carpets, snow layers, pressure plates etc. (collision top <= 0.25) are plain walkable.
 *  - a +1Y rise over stairs/slabs (or any <=0.6 ledge) is a WALK (player step-height); only a
 *    full-block rise gets F_JUMP.
 *  - traversability is capability-parameterized via NavCaps (lava-walk boots, fire res, drop and
 *    wade tolerances) -- conservative defaults, AI/Master override (the recurring pattern).
 */
final class PlayerNodeEvaluator {

    static final long NONE = Long.MIN_VALUE;

    private static final int C_OPEN = 0;
    private static final int C_BLOCKED = 1;
    private static final int C_DOOR = 2;
    private static final int C_WATER = 3;

    private PlayerNodeEvaluator() {}

    /**
     * Resolve the node reached by moving one cell in (dx,dz) from feet cell (x,y,z).
     * Returns NONE, or a packed {y,flags} (see pack/resY/resFlags). Handles same-level moves,
     * a one-block step/jump up, drops of up to NavCaps.maxDrop, wading, doors, and diagonals
     * (flat, clean, corner-safe only).
     */
    static long resolve(Level lvl, BlockPos.MutableBlockPos m, int x, int y, int z, int dx, int dz) {
        int nx = x + dx;
        int nz = z + dz;
        boolean diag = dx != 0 && dz != 0;
        if (diag) {
            // no corner clipping: both orthogonal columns must be fully open
            if (cell(lvl, m, x + dx, y, z) != C_OPEN || cell(lvl, m, x + dx, y + 1, z) != C_OPEN) {
                return NONE;
            }
            if (cell(lvl, m, x, y, z + dz) != C_OPEN || cell(lvl, m, x, y + 1, z + dz) != C_OPEN) {
                return NONE;
            }
        }

        int body = cell(lvl, m, nx, y, nz);
        int head = cell(lvl, m, nx, y + 1, nz);

        if (body == C_BLOCKED) {
            if (diag) {
                return NONE;
            }
            return stepUp(lvl, m, x, y, z, nx, nz);
        }

        boolean bodyPass = body == C_OPEN || body == C_DOOR || body == C_WATER;
        boolean headPass = head == C_OPEN || head == C_DOOR || head == C_WATER;
        if (!bodyPass || !headPass) {
            return NONE;
        }

        byte fl = 0;
        if (body == C_DOOR || head == C_DOOR) {
            fl |= NavPath.F_OPEN;
        }

        if (body == C_WATER) {
            if (diag) {
                return NONE;
            }
            return wade(lvl, m, nx, y, nz, fl);
        }

        double top = floorTop(lvl, m, nx, y, nz);
        if (top >= 0 && top <= 1.0) {
            if (diag && fl != 0) {
                return NONE;
            }
            return pack(y, fl);
        }
        if (diag || fl != 0) {
            return NONE;
        }
        return drop(lvl, m, nx, y, nz);
    }

    /** One-block rise: walk if the blocker is stairs/slab/low ledge (player step-height), else jump. */
    private static long stepUp(Level lvl, BlockPos.MutableBlockPos m, int x, int y, int z, int nx, int nz) {
        if (cell(lvl, m, x, y + 2, z) != C_OPEN) {
            return NONE; // no headroom to rise in our own column
        }
        if (cell(lvl, m, nx, y + 1, nz) != C_OPEN || cell(lvl, m, nx, y + 2, nz) != C_OPEN) {
            return NONE;
        }
        double top = floorTop(lvl, m, nx, y + 1, nz);
        if (top < 0 || top > 1.0) {
            return NONE; // fences/walls (1.5 collision) land here and stay unwalkable
        }
        BlockState blocker = lvl.getBlockState(m.set(nx, y, nz));
        boolean walk = blocker.is(BlockTags.STAIRS) || blocker.is(BlockTags.SLABS)
                || maxY(blocker.getCollisionShape(lvl, m)) <= 0.6;
        return pack(y + 1, walk ? 0 : NavPath.F_JUMP);
    }

    /** Water entry: wade/swim only when a floor exists within NavCaps.maxWadeDepth; deeper is a
     *  CROSSING (boat territory -- handled by a mode above the pather, later). */
    private static long wade(Level lvl, BlockPos.MutableBlockPos m, int nx, int y, int nz, byte fl) {
        fl |= NavPath.F_WATER;
        for (int d = 1; d <= NavCaps.maxWadeDepth + 1; d++) {
            int below = cell(lvl, m, nx, y - d, nz);
            if (below == C_BLOCKED) {
                if (d > NavCaps.maxWadeDepth) {
                    return NONE;
                }
                return pack(y, fl);
            }
            if (below != C_WATER) {
                return NONE;
            }
        }
        return NONE;
    }

    /** Descend 1..NavCaps.maxDrop blocks; 2+ marks F_DROP. Water landing is allowed. */
    private static long drop(Level lvl, BlockPos.MutableBlockPos m, int nx, int y, int nz) {
        for (int d = 1; d <= NavCaps.maxDrop; d++) {
            int by = y - d;
            int c = cell(lvl, m, nx, by, nz);
            if (c == C_WATER) {
                return pack(by, (byte) (NavPath.F_WATER | (d >= 2 ? NavPath.F_DROP : 0)));
            }
            if (c != C_OPEN) {
                return NONE;
            }
            double t = floorTop(lvl, m, nx, by, nz);
            if (t >= 0 && t <= 1.0) {
                return pack(by, d >= 2 ? NavPath.F_DROP : (byte) 0);
            }
        }
        return NONE;
    }

    /** Classify the body cell at (x,y,z). */
    private static int cell(Level lvl, BlockPos.MutableBlockPos m, int x, int y, int z) {
        BlockState s = lvl.getBlockState(m.set(x, y, z));
        if (s.isAir()) {
            return C_OPEN;
        }
        if (s.getFluidState().is(FluidTags.WATER)) {
            return C_WATER;
        }
        if (s.getFluidState().is(FluidTags.LAVA)) {
            return C_BLOCKED;
        }
        if (s.is(BlockTags.FIRE)) {
            return NavCaps.fireRes ? C_OPEN : C_BLOCKED;
        }
        boolean door = s.getBlock() instanceof DoorBlock;
        boolean gate = s.getBlock() instanceof FenceGateBlock;
        if (door || gate) {
            if (s.hasProperty(BlockStateProperties.OPEN) && s.getValue(BlockStateProperties.OPEN)) {
                return C_OPEN;
            }
            if (door && s.is(Blocks.IRON_DOOR)) {
                return C_BLOCKED; // no hand-open
            }
            return C_DOOR;
        }
        VoxelShape shape = s.getCollisionShape(lvl, m);
        if (shape.isEmpty()) {
            return C_OPEN; // rails, torches, grass, ladders...
        }
        if (maxY(shape) <= 0.25) {
            return C_OPEN; // carpet, snow layer, pressure plate
        }
        return C_BLOCKED;
    }

    /**
     * Standable top surface of the block UNDER feet cell (x,y-1,z): 0..1, or -1 if not standable.
     * Lava is standable only with lava-walk; magma only with fire res; cactus/campfires never.
     */
    private static double floorTop(Level lvl, BlockPos.MutableBlockPos m, int x, int y, int z) {
        BlockState s = lvl.getBlockState(m.set(x, y - 1, z));
        if (s.getFluidState().is(FluidTags.LAVA)) {
            return NavCaps.lavaWalk ? 1.0 : -1;
        }
        if (s.is(Blocks.MAGMA_BLOCK) && !NavCaps.fireRes) {
            return -1;
        }
        if (s.is(Blocks.CACTUS) || s.is(BlockTags.CAMPFIRES)) {
            return -1;
        }
        VoxelShape shape = s.getCollisionShape(lvl, m);
        if (shape.isEmpty()) {
            return -1;
        }
        double top = maxY(shape);
        return top >= 0.4 ? top : -1;
    }

    private static double maxY(VoxelShape shape) {
        return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
    }

    static long pack(int y, int flags) {
        return ((long) (y + 2048) << 8) | (flags & 0xFFL);
    }

    static int resY(long v) {
        return (int) (v >>> 8) - 2048;
    }

    static byte resFlags(long v) {
        return (byte) (v & 0xFF);
    }
}
