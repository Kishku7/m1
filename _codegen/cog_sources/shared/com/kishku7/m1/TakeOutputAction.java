package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * BULK SMELTER COLLECTION: walk every furnace in range and take ONLY its output slot, leaving the
 * input and the fuel alone.
 *
 * <p>Why this exists (Master, 2026-08-01): "empty every furnace in the bank" was six socket
 * commands per furnace -- face, look, place, slots, slot, close -- and the bank turned out to be
 * SIXTEEN furnaces in an unbroken row. Worse, the old targeted scan only ever named the nearest
 * match, so walking-and-rescanning silently skipped three of them and the job was reported as
 * nearly finished when barely a third was done. This is the same answer as {@link MineAreaAction}:
 * one command, the mod owns the loop, progress arrives as {@code [agent]} lines.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Finds furnaces by VOLUME (not line-of-sight), so a bank behind a wall still counts.
 *     Covers furnace, blast furnace and smoker.</li>
 *   <li>Walks to each in turn, nearest first, and opens it BY COORDINATE (no crosshair aiming --
 *     the aiming was the single biggest source of wasted commands and wrong-chest opens).</li>
 *   <li>Takes the OUTPUT slot only (menu slot 2). Input (0) and fuel (1) are never touched.</li>
 *   <li>Skips a furnace whose output is empty without opening a second time, and never wedges:
 *     anything that will not open within a budget is reported and skipped.</li>
 *   <li>Stops early and says so if the inventory fills up, rather than silently dropping items.</li>
 * </ul>
 */
public final class TakeOutputAction implements MinecraftAction {

    /** Furnace-family blocks whose menu is input(0) / fuel(1) / output(2). */
    private static final String[] KINDS = {"furnace", "blast_furnace", "smoker"};

    private static final int OUTPUT_SLOT = 2;
    private static final int MAX_RADIUS = 32;
    private static final double REACH_STOP = 2.0;   // stand close: server reach is ~4.4 from the eye
    private static final int APPROACH_TICKS = 400;
    private static final int OPEN_WAIT_TICKS = 40;  // server round-trip for the menu to appear
    private static final int SETTLE_TICKS = 4;
    private static final int BUDGET_TICKS = 24000;  // 20 min

    private enum Phase { PLAN, NEXT, APPROACH, OPEN, TAKE, CLOSE, DONE }

    private final int radius;

    private Phase phase = Phase.PLAN;
    private List<BlockPos> targets;
    private int cursor;
    private int emptied, taken, skipped, alreadyEmpty;
    private int ticksRun;
    private int phaseTicks;
    private boolean inventoryFull;

    public TakeOutputAction(int radius) {
        this.radius = Math.max(1, Math.min(MAX_RADIUS, radius));
    }

    @Override
    public String name() {
        return "take output";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.level == null) {
            ctx.report(ReportClass.STATUS, "take output: not in world");
            return StepResult.FAILED;
        }
        LocalPlayer p = mc.player;
        if (++ticksRun > BUDGET_TICKS) {
            Crafting.close(mc);
            MoveControl.stop();
            ctx.report(ReportClass.STATUS, "take output: time budget spent -- " + summary());
            return StepResult.FAILED;
        }

        switch (phase) {
            case PLAN:    return plan(ctx, mc, p);
            case NEXT:    return next(ctx);
            case APPROACH: return approach(ctx, mc, p);
            case OPEN:    return open(ctx, mc, p);
            case TAKE:    return take(ctx, mc, p);
            case CLOSE:   return closeUp(ctx, mc);
            default:      return StepResult.DONE;
        }
    }

    private StepResult plan(ActionContext ctx, Minecraft mc, LocalPlayer p) {
        BlockPos origin = BlockPos.containing(p.getX(), p.getY(), p.getZ());
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int rr = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > rr) continue;
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState st = mc.level.getBlockState(m);
                    if (st.isAir()) continue;
                    if (!isFurnace(ScreenOps.blockShort(st))) continue;
                    found.add(m.immutable());
                }
            }
        }
        found.sort(Comparator.comparingDouble(b -> p.getEyePosition().distanceToSqr(
                b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)));
        targets = found;
        cursor = 0;
        if (targets.isEmpty()) {
            ctx.report(ReportClass.STATUS, "take output: no furnaces within " + radius);
            phase = Phase.DONE;
            return StepResult.DONE;
        }
        ctx.report(ReportClass.STATUS, "take output: " + targets.size()
                + " furnace(s) within " + radius + " -- output slot only, leaving input + fuel");
        phase = Phase.NEXT;
        return StepResult.RUNNING;
    }

    private static boolean isFurnace(String id) {
        for (String k : KINDS) {
            if (id.equals(k)) return true;
        }
        return false;
    }

    private StepResult next(ActionContext ctx) {
        if (inventoryFull) {
            ctx.report(ReportClass.STATUS, "take output: inventory is FULL -- stopping early with "
                    + summary() + ". Stow what I am carrying and re-issue.");
            phase = Phase.DONE;
            return StepResult.DONE;
        }
        if (cursor >= targets.size()) {
            ctx.report(ReportClass.STATUS, "take output: done -- " + summary());
            phase = Phase.DONE;
            return StepResult.DONE;
        }
        phaseTicks = 0;
        phase = Phase.APPROACH;
        return StepResult.RUNNING;
    }

    private StepResult approach(ActionContext ctx, Minecraft mc, LocalPlayer p) {
        BlockPos t = targets.get(cursor);
        double d = p.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(t));
        if (d <= 4.0) {
            MoveControl.stop();
            phaseTicks = 0;
            phase = Phase.OPEN;
            return StepResult.RUNNING;
        }
        if (MoveControl.isActive()) {
            if (++phaseTicks > APPROACH_TICKS) {
                MoveControl.stop();
                skip(ctx, "could not walk to it");
            }
            return StepResult.RUNNING;
        }
        if (phaseTicks > 0) {          // a move already finished and we are still out of reach
            skip(ctx, "out of reach after approaching");
            return StepResult.RUNNING;
        }
        phaseTicks = 1;
        ScreenOps.startMove(mc, p, t.getX() + 0.5, t.getY(), t.getZ() + 0.5, REACH_STOP,
                "take output approach");
        return StepResult.RUNNING;
    }

    private StepResult open(ActionContext ctx, Minecraft mc, LocalPlayer p) {
        BlockPos t = targets.get(cursor);
        if (phaseTicks == 0) {
            String r = ScreenOps.useAt(mc, t, "take output");
            if (!r.startsWith("OK")) {
                skip(ctx, r);
                return StepResult.RUNNING;
            }
        }
        phaseTicks++;
        if (p.containerMenu != null && p.containerMenu != p.inventoryMenu) {
            phaseTicks = 0;
            phase = Phase.TAKE;
            return StepResult.RUNNING;
        }
        if (phaseTicks > OPEN_WAIT_TICKS) {
            skip(ctx, "menu never opened");
        }
        return StepResult.RUNNING;
    }

    private StepResult take(ActionContext ctx, Minecraft mc, LocalPlayer p) {
        if (phaseTicks++ < SETTLE_TICKS) {
            return StepResult.RUNNING;   // let the menu contents sync before reading them
        }
        BlockPos t = targets.get(cursor);
        if (p.containerMenu == null || p.containerMenu.slots.size() <= OUTPUT_SLOT) {
            skip(ctx, "unexpected menu shape");
            return StepResult.RUNNING;
        }
        int before = p.containerMenu.getSlot(OUTPUT_SLOT).getItem().getCount();
        if (before == 0) {
            alreadyEmpty++;
            phase = Phase.CLOSE;
            return StepResult.RUNNING;
        }
        String what = Crafting.itemId(p.containerMenu.getSlot(OUTPUT_SLOT).getItem());
        Crafting.click(mc, OUTPUT_SLOT, 0, ContainerCompat.Mode.QUICK_MOVE);
        int after = p.containerMenu.getSlot(OUTPUT_SLOT).getItem().getCount();
        if (after >= before) {
            inventoryFull = true;      // quick-move had nowhere to put it
            ctx.report(ReportClass.STATUS, "take output: could not move " + before + "x " + what
                    + " out of (" + t.getX() + "," + t.getY() + "," + t.getZ() + ") -- inventory full");
        } else {
            emptied++;
            taken += (before - after);
            ctx.report(ReportClass.STATUS, String.format(Locale.ROOT,
                    "take output: +%dx %s from (%d,%d,%d)  [%d/%d]",
                    before - after, what, t.getX(), t.getY(), t.getZ(), cursor + 1, targets.size()));
        }
        phase = Phase.CLOSE;
        return StepResult.RUNNING;
    }

    private StepResult closeUp(ActionContext ctx, Minecraft mc) {
        Crafting.close(mc);
        cursor++;
        phaseTicks = 0;
        phase = Phase.NEXT;
        return StepResult.RUNNING;
    }

    private void skip(ActionContext ctx, String why) {
        BlockPos t = targets.get(cursor);
        skipped++;
        ctx.report(ReportClass.STATUS, "take output: skipping (" + t.getX() + "," + t.getY() + ","
                + t.getZ() + ") -- " + why);
        cursor++;
        phaseTicks = 0;
        phase = Phase.NEXT;
    }

    private String summary() {
        return emptied + " emptied (" + taken + " items), " + alreadyEmpty + " already empty, "
                + skipped + " skipped, "
                + (targets == null ? 0 : Math.max(0, targets.size() - cursor)) + " left";
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) Crafting.close(mc);   // never leave a container menu open for the next action
        MoveControl.stop();
        phaseTicks = 0;
        if (phase == Phase.OPEN || phase == Phase.TAKE || phase == Phase.CLOSE) {
            phase = Phase.NEXT;               // re-approach this furnace cleanly on resume
        }
    }
}
