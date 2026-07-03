package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Composite: "sleep in a nearby bed" as ONE smooth bot function (Master, 2026-07-02, after the
 * 702b bed fumble where the bot stood ON the bed and used {@code interact} blind).
 *
 * Flow: gate (night/thunderstorm) -> FIND a usable (unoccupied) bed within range -> path to a
 * standable cell BESIDE it (never onto it) -> FACE the bed until the crosshair is actually on a
 * bed block -> use -> VERIFY {@code player.isSleeping()}, retrying the aim+use a couple of times.
 * Every failure reports a concrete reason (daytime, none found, unreachable, use had no effect).
 */
public final class SleepAction implements MinecraftAction {

    private static final int SCAN_R = 24;        // horizontal bed search radius
    private static final int SCAN_Y = 4;         // vertical bed search half-height
    private static final int FACE_TICKS_MAX = 10;
    private static final int VERIFY_TICKS = 15;
    private static final int MAX_USE_TRIES = 3;

    private enum St { FIND, NAVIGATE, FACE, VERIFY, DEPLOY_BAG }

    private St st = St.FIND;
    private BlockPos bed;        // the bed (or sleeping-bag) block we will click
    private BlockPos approach;   // the cell we stand in
    private int faceTicks;
    private int verifyTicks;
    private int useTries;
    private int deployTicks;
    private boolean triedBag;

    @Override
    public String name() {
        return "sleep";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.level == null) {
            ctx.report(ReportClass.STATUS, "sleep: not in world");
            return StepResult.FAILED;
        }
        LocalPlayer p = mc.player;
        Level lvl = mc.level;

        switch (st) {
            case FIND: {
                if (lvl.isBrightOutside() && !lvl.isThundering()) {
                    ctx.report(ReportClass.STATUS, "sleep: cannot sleep now (daytime, no thunderstorm)");
                    return StepResult.FAILED;
                }
                bed = findBed(lvl, p.blockPosition());
                if (bed == null) {
                    // No natural bed. If a Travelers Backpack sleeping bag is available (loose item
                    // or attached to the worn/carried backpack), deploy it and sleep in place.
                    if (!triedBag && BagSleep.available(mc)) {
                        triedBag = true;
                        st = St.DEPLOY_BAG;
                        deployTicks = 0;
                        ctx.report(ReportClass.STATUS, "sleep: no bed nearby -- deploying a sleeping bag");
                        return StepResult.RUNNING;
                    }
                    ctx.report(ReportClass.STATUS, "sleep: no usable bed within " + SCAN_R
                            + " blocks and no sleeping bag available");
                    return StepResult.FAILED;
                }
                approach = findApproach(lvl, bed);
                if (approach == null) {
                    ctx.report(ReportClass.STATUS, "sleep: bed at " + bed.toShortString()
                            + " has no standable cell beside it");
                    return StepResult.FAILED;
                }
                ctx.report(ReportClass.STATUS, "sleep: bed at " + bed.toShortString()
                        + "; walking to " + approach.toShortString());
                String r = ScreenOps.startMove(mc, p, approach.getX() + 0.5, approach.getY(),
                        approach.getZ() + 0.5, 0.4, "sleep approach");
                if (!r.startsWith("OK")) {
                    ctx.report(ReportClass.STATUS, "sleep: cannot path to the bed (" + r + ")");
                    return StepResult.FAILED;
                }
                st = St.NAVIGATE;
                return StepResult.RUNNING;
            }
            case NAVIGATE: {
                if (MoveControl.isActive()) {
                    return StepResult.RUNNING;
                }
                double d = Math.hypot(approach.getX() + 0.5 - p.getX(), approach.getZ() + 0.5 - p.getZ());
                if (d > 1.6) {
                    ctx.report(ReportClass.STATUS, "sleep: could not reach the bed ("
                            + MoveControl.status() + ", " + String.format(java.util.Locale.ROOT, "%.1f", d)
                            + "m short)");
                    return StepResult.FAILED;
                }
                st = St.FACE;
                faceTicks = 0;
                return StepResult.RUNNING;
            }
            case FACE: {
                aimAt(p, bed.getX() + 0.5, bed.getY() + 0.4, bed.getZ() + 0.5);
                if (crosshairOnBed(mc)) {
                    Crafting.place(mc);
                    useTries++;
                    st = St.VERIFY;
                    verifyTicks = 0;
                    return StepResult.RUNNING;
                }
                if (++faceTicks > FACE_TICKS_MAX) {
                    // try the other half of the bed once, then give up
                    BlockPos other = otherHalf(lvl, bed);
                    if (other != null && !other.equals(bed)) {
                        bed = other;
                        faceTicks = 0;
                        return StepResult.RUNNING;
                    }
                    ctx.report(ReportClass.STATUS,
                            "sleep: cannot get the bed under the crosshair (obstructed?)");
                    return StepResult.FAILED;
                }
                return StepResult.RUNNING;
            }
            case VERIFY: {
                if (p.isSleeping()) {
                    ctx.report(ReportClass.STATUS, "sleep: sleeping in the bed at " + bed.toShortString());
                    return StepResult.DONE;
                }
                if (++verifyTicks >= VERIFY_TICKS) {
                    if (useTries < MAX_USE_TRIES) {
                        st = St.FACE;
                        faceTicks = 0;
                        return StepResult.RUNNING;
                    }
                    String why = lvl.isBrightOutside() && !lvl.isThundering() ? "it turned day"
                            : "monsters nearby, or the bed is obstructed/too far";
                    ctx.report(ReportClass.STATUS, "sleep: bed use had no effect (" + why + ")");
                    return StepResult.FAILED;
                }
                return StepResult.RUNNING;
            }
            case DEPLOY_BAG: {
                if (deployTicks++ == 0) {
                    BagSleep.deploy(mc); // select + aim-down + use the bag item to place the block
                    return StepResult.RUNNING;
                }
                if (deployTicks < 10) {
                    return StepResult.RUNNING; // let the block placement settle
                }
                bed = findBed(lvl, p.blockPosition()); // the placed sleeping-bag block IS a BedBlock
                if (bed == null) {
                    ctx.report(ReportClass.STATUS, "sleep: have a sleeping bag but could not deploy it here"
                            + " (Travelers Backpack bag placement -- needs 2 clear cells on flat ground;"
                            + " if it keeps failing, place a bed instead)");
                    return StepResult.FAILED;
                }
                approach = findApproach(lvl, bed);
                if (approach == null) {
                    approach = p.blockPosition(); // stand where we are; the bag is at our feet
                }
                st = St.FACE;
                faceTicks = 0;
                return StepResult.RUNNING;
            }
            default:
                return StepResult.FAILED;
        }
    }

    /** Nearest unoccupied bed block around the player. */
    private static BlockPos findBed(Level lvl, BlockPos c) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-SCAN_R, -SCAN_Y, -SCAN_R),
                c.offset(SCAN_R, SCAN_Y, SCAN_R))) {
            BlockState s = lvl.getBlockState(bp);
            if (!s.is(BlockTags.BEDS)) {
                continue;
            }
            if (s.hasProperty(BlockStateProperties.OCCUPIED) && s.getValue(BlockStateProperties.OCCUPIED)) {
                continue;
            }
            double d = bp.distSqr(c);
            if (d < bd) {
                bd = d;
                best = bp.immutable();
            }
        }
        return best;
    }

    /** A standable cell cardinally beside the bed (never a bed cell itself). */
    private static BlockPos findApproach(Level lvl, BlockPos bed) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos side = bed.relative(d);
            if (standable(lvl, side)) {
                return side;
            }
        }
        return null;
    }

    private static boolean standable(Level lvl, BlockPos pos) {
        BlockState feet = lvl.getBlockState(pos);
        BlockState head = lvl.getBlockState(pos.above());
        if (feet.is(BlockTags.BEDS) || head.is(BlockTags.BEDS)) {
            return false;
        }
        return passable(lvl, pos, feet) && passable(lvl, pos.above(), head)
                && !lvl.getBlockState(pos.below()).getCollisionShape(lvl, pos.below()).isEmpty();
    }

    private static boolean passable(Level lvl, BlockPos p, BlockState s) {
        if (s.isAir()) {
            return true;
        }
        VoxelShape sh = s.getCollisionShape(lvl, p);
        return sh.isEmpty() || sh.max(Direction.Axis.Y) <= 0.25;
    }

    /** The other half of the bed (HEAD<->FOOT), if present. */
    private static BlockPos otherHalf(Level lvl, BlockPos bed) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos n = bed.relative(d);
            if (lvl.getBlockState(n).is(BlockTags.BEDS)) {
                return n.immutable();
            }
        }
        return null;
    }

    private static void aimAt(LocalPlayer p, double tx, double ty, double tz) {
        double dx = tx - p.getX();
        double dz = tz - p.getZ();
        double dy = ty - (p.getY() + p.getEyeHeight());
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
    }

    private static boolean crosshairOnBed(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult bhr)) {
            return false;
        }
        return mc.level.getBlockState(bhr.getBlockPos()).is(BlockTags.BEDS);
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        MoveControl.stop();
    }
}
