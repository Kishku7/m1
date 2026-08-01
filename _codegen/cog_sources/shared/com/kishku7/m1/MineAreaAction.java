package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * BULK MINING: clear every breakable block in an axis-aligned box, as ONE queued agent job.
 *
 * <p>Why this exists (Master, 2026-08-01): {@code mine [x y z]} is strictly one block per socket
 * command, so clearing a ~55-column x ~12-level slice cost 600+ round-trips and was not a usable
 * workflow. This action walks the volume itself and reports progress as {@code [agent]} lines, so
 * the AI issues one command and then just listens.
 *
 * <p>Behaviour that the live excavation session specifically demanded:
 * <ul>
 *   <li><b>Air gaps are free.</b> The ridge was NOT solid -- mid-column air pockets are exactly
 *       what forced per-block probing before. Air cells are skipped in-loop with no dig, no
 *       round-trip and no tick cost.</li>
 *   <li><b>Top-down order.</b> Layers are cleared highest Y first so nothing is mined out from
 *       under a gravity block or the player; within a layer the nearest cell goes first.</li>
 *   <li><b>Reposition, do not stall.</b> A cell out of reach gets one approach (the pather) before
 *       digging; if that fails, the dig is still attempted from range (the crosshair ray tunnels
 *       through what is in between), and only then is the cell recorded as skipped.</li>
 *   <li><b>Drops.</b> At range, drops land far away and despawn uncollected. When collection is on
 *       (default) the job finishes by walking the loose item entities in and around the box.</li>
 * </ul>
 *
 * <p>Unbreakable cells (bedrock/barrier, {@code getDestroySpeed < 0}) and pure liquids are never
 * attempted -- they would burn the whole dig budget for nothing.
 */
public final class MineAreaAction implements MinecraftAction {

    /** Hard cap on the volume one job may enumerate (64^3-ish); larger boxes must be split. */
    public static final int MAX_VOLUME = 65536;

    private static final int DIG_TICKS = 200;        // per-block dig budget (same as 'mine')
    private static final int APPROACH_TICKS = 400;   // per-cell approach budget
    private static final double REACH_TRY = 5.0;     // beyond this we approach before digging
    private static final double APPROACH_STOP = 3.0;
    private static final int RETRIES = 1;            // dig attempts per cell after the first
    private static final int REPORT_EVERY = 25;      // blocks broken between progress lines
    private static final int COLLECT_BUDGET = 2400;  // ticks for the whole pickup sweep
    private static final int COLLECT_MAX_ITEMS = 64;
    private static final double COLLECT_MARGIN = 6.0;

    private enum Phase { PLAN, DIG, COLLECT, DONE }

    private final int x1, y1, z1, x2, y2, z2;
    private final boolean collect;
    private final int budgetTicks;

    private Phase phase = Phase.PLAN;
    private List<BlockPos> cells;
    private int cursor;
    private int broken, skippedAir, skippedHard, failed;
    private int ticksRun;

    // per-cell state
    private boolean digging;
    private boolean approached;
    private int attempts;
    private int cellTicks;

    // collect state
    private final Set<Integer> collectTried = new HashSet<>();
    private int collectTicks;
    private int collected;
    private boolean walkingToItem;

    public MineAreaAction(int x1, int y1, int z1, int x2, int y2, int z2, boolean collect, int budgetTicks) {
        this.x1 = Math.min(x1, x2); this.x2 = Math.max(x1, x2);
        this.y1 = Math.min(y1, y2); this.y2 = Math.max(y1, y2);
        this.z1 = Math.min(z1, z2); this.z2 = Math.max(z1, z2);
        this.collect = collect;
        this.budgetTicks = budgetTicks;
    }

    /** Cells in the box, ignoring what is actually in them (the enumeration cost, not the dig cost). */
    public static long volumeOf(int x1, int y1, int z1, int x2, int y2, int z2) {
        long dx = Math.abs((long) x2 - x1) + 1;
        long dy = Math.abs((long) y2 - y1) + 1;
        long dz = Math.abs((long) z2 - z1) + 1;
        return dx * dy * dz;
    }

    @Override
    public String name() {
        return "mine area";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.level == null) {
            ctx.report(ReportClass.STATUS, "mine area: not in world");
            return StepResult.FAILED;
        }
        LocalPlayer p = mc.player;

        if (++ticksRun > budgetTicks) {
            MineControl.stop();
            MoveControl.stop();
            ctx.report(ReportClass.STATUS, "mine area: time budget spent -- " + summary()
                    + " (re-issue to continue where this left off)");
            return StepResult.FAILED;
        }

        switch (phase) {
            case PLAN:
                return plan(ctx, p);
            case DIG:
                return dig(ctx, mc, p);
            case COLLECT:
                return collectStep(ctx, mc, p);
            default:
                return StepResult.DONE;
        }
    }

    // ---- phase 1: enumerate the box, top-down, nearest-first inside each layer ----
    private StepResult plan(ActionContext ctx, LocalPlayer p) {
        long vol = volumeOf(x1, y1, z1, x2, y2, z2);
        if (vol > MAX_VOLUME) {
            ctx.report(ReportClass.STATUS, "mine area: box is " + vol + " cells (cap " + MAX_VOLUME
                    + ") -- split it into smaller boxes");
            return StepResult.FAILED;
        }
        final double px = p.getX();
        final double pz = p.getZ();
        List<BlockPos> list = new ArrayList<>();
        for (int y = y2; y >= y1; y--) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    list.add(new BlockPos(x, y, z));
                }
            }
        }
        list.sort(Comparator
                .comparingInt((BlockPos b) -> -b.getY())
                .thenComparingDouble(b -> {
                    double dx = b.getX() + 0.5 - px;
                    double dz = b.getZ() + 0.5 - pz;
                    return dx * dx + dz * dz;
                }));
        cells = list;
        cursor = 0;
        phase = Phase.DIG;
        ctx.report(ReportClass.STATUS, String.format(Locale.ROOT,
                "mine area: (%d,%d,%d)-(%d,%d,%d) = %d cells, top-down, collect=%s",
                x1, y1, z1, x2, y2, z2, cells.size(), collect ? "on" : "off"));
        return StepResult.RUNNING;
    }

    // ---- phase 2: walk the cell list ----
    private StepResult dig(ActionContext ctx, Minecraft mc, LocalPlayer p) {
        if (MineControl.isActive() || MoveControl.isActive()) {
            cellTicks++;
            if (cellTicks > APPROACH_TICKS + DIG_TICKS + 40) {
                MineControl.stop();
                MoveControl.stop();   // wedged on this cell: drop it and move on
                nextCell(ctx, true);
            }
            return StepResult.RUNNING;
        }

        if (digging) {
            digging = false;
            BlockPos t = cells.get(cursor);
            if (mc.level.getBlockState(t).isAir()) {
                broken++;
                if ((broken % REPORT_EVERY) == 0) {
                    ctx.report(ReportClass.STATUS, "mine area: " + summary() + ", at "
                            + t.getX() + "," + t.getY() + "," + t.getZ());
                }
                nextCell(ctx, false);
            } else if (attempts <= RETRIES) {
                attempts++;          // one more go (the ray may have been eating a block in between)
            } else {
                nextCell(ctx, true);
            }
            return StepResult.RUNNING;
        }

        // advance past cells that need no work at all -- air gaps are free, this is the whole point
        while (cursor < cells.size()) {
            BlockPos t = cells.get(cursor);
            BlockState st = mc.level.getBlockState(t);
            if (st.isAir()) { skippedAir++; cursor++; resetCell(); continue; }
            if (st.getBlock() instanceof LiquidBlock) { skippedHard++; cursor++; resetCell(); continue; }
            if (st.getDestroySpeed(mc.level, t) < 0) { skippedHard++; cursor++; resetCell(); continue; }
            break;
        }
        if (cursor >= cells.size()) {
            ctx.report(ReportClass.STATUS, "mine area: dig complete -- " + summary());
            if (collect) {
                phase = Phase.COLLECT;
                return StepResult.RUNNING;
            }
            phase = Phase.DONE;
            return StepResult.DONE;
        }

        BlockPos t = cells.get(cursor);
        double eyeDist = Math.sqrt(p.getEyePosition().distanceToSqr(
                t.getX() + 0.5, t.getY() + 0.5, t.getZ() + 0.5));
        if (!approached && eyeDist > REACH_TRY) {
            approached = true;   // one approach per cell, then dig from wherever we ended up
            cellTicks = 0;
            ScreenOps.startMove(mc, p, t.getX() + 0.5, t.getY(), t.getZ() + 0.5,
                    APPROACH_STOP, "mine area approach");
            return StepResult.RUNNING;
        }

        cellTicks = 0;
        digging = true;
        MineControl.start(t, DIG_TICKS);
        return StepResult.RUNNING;
    }

    private void nextCell(ActionContext ctx, boolean asFailure) {
        if (asFailure) {
            failed++;
            if (failed == 1 || (failed % 20) == 0) {
                BlockPos t = cells.get(cursor);
                ctx.report(ReportClass.STATUS, "mine area: could not break ("
                        + t.getX() + "," + t.getY() + "," + t.getZ() + ") -- skipping ("
                        + failed + " skipped so far)");
            }
        }
        cursor++;
        resetCell();
    }

    private void resetCell() {
        digging = false;
        approached = false;
        attempts = 0;
        cellTicks = 0;
    }

    // ---- phase 3: sweep up the drops so a ranged dig does not just despawn the yield ----
    private StepResult collectStep(ActionContext ctx, Minecraft mc, LocalPlayer p) {
        if (MoveControl.isActive()) {
            collectTicks++;
            if (collectTicks > COLLECT_BUDGET) {
                MoveControl.stop();
                return finishCollect(ctx);
            }
            return StepResult.RUNNING;
        }
        if (walkingToItem) {
            walkingToItem = false;
            collected++;
        }
        if (++collectTicks > COLLECT_BUDGET || collectTried.size() >= COLLECT_MAX_ITEMS) {
            return finishCollect(ctx);
        }

        AABB box = new AABB(x1, y1, z1, x2 + 1.0, y2 + 1.0, z2 + 1.0).inflate(COLLECT_MARGIN);
        List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class, box,
                e -> e.isAlive() && !collectTried.contains(Integer.valueOf(e.getId())));
        ItemEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (ItemEntity e : items) {
            double d = e.distanceToSqr(p);
            if (d < bestSq) { bestSq = d; best = e; }
        }
        if (best == null) {
            return finishCollect(ctx);
        }
        collectTried.add(Integer.valueOf(best.getId()));
        walkingToItem = true;
        ScreenOps.startMove(mc, p, best.getX(), best.getY(), best.getZ(), 0.6, "mine area collect");
        return StepResult.RUNNING;
    }

    private StepResult finishCollect(ActionContext ctx) {
        MoveControl.stop();
        phase = Phase.DONE;
        ctx.report(ReportClass.STATUS, "mine area: done -- " + summary()
                + ", walked " + collected + " drop pile(s)");
        return StepResult.DONE;
    }

    private String summary() {
        return broken + " broken, " + skippedAir + " air, " + skippedHard + " unbreakable/liquid, "
                + failed + " skipped, " + (cells == null ? 0 : cells.size() - cursor) + " left";
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        MineControl.stop();
        MoveControl.stop();
        resetCell();          // re-check this cell from the new world state on resume
        walkingToItem = false;
    }
}
