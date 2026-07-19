package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Leaf action that throws item(s) on the ground -- the Q-key equivalent (session-703a gap: the
 * agent had no way to toss the player head). Drops via {@code LocalPlayer.drop(boolean)}, the
 * vanilla drop-key path (proper serverbound action packet, desync-safe), which only drops the
 * HELD stack -- so non-held sources are first brought to hand: hotbar slots are selected
 * ({@link Crafting#hold}), and main-inv / armor / offhand slots are SWAP-clicked into the
 * selected hotbar slot through the always-available player inventoryMenu.
 *
 * <p>Spec forms (resolved via {@link ItemInfo#resolve}): empty/hand = held stack; hb1-9; inv N;
 * head|chest|legs|feet|offhand; or an item name/id search across all 41 slots. Count: n single
 * drops (one per tick), or ALL = the whole stack in one throw.
 */
public final class DropAction implements MinecraftAction {

    /** Whole-stack sentinel for {@code count}. */
    public static final int ALL = -1;

    private final String spec;
    private final int count;

    private int phase;      // 0 = resolve + bring to hand, 1 = dropping
    private int dropped;
    private String what = "?";

    public DropAction(String spec, int count) {
        this.spec = (spec == null) ? "" : spec.trim();
        this.count = count;
    }

    /** Back-compat: plain drop of the held item (1, or the stack with {@code all}). */
    public DropAction(boolean all) {
        this("", all ? ALL : 1);
    }

    @Override
    public String name() {
        return "drop";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, "drop: not in world");
            return StepResult.FAILED;
        }
        if (phase == 0) {
            return bringToHand(ctx, mc);
        }
        return dropTick(ctx, mc);
    }

    /** Phase 0: locate the target stack and make it the held item. */
    private StepResult bringToHand(ActionContext ctx, Minecraft mc) {
        int idx = ItemInfo.resolve(mc, spec);
        if (idx == -2) {
            ctx.report(ReportClass.STATUS,
                    "drop: bad slot spec '" + spec + "' (try hand|hb1-9|inv N|head|chest|legs|feet|offhand|<name>)");
            return StepResult.FAILED;
        }
        if (idx == -1) {
            ctx.report(ReportClass.STATUS, "drop: nothing matching '" + spec + "' in inventory");
            return StepResult.FAILED;
        }
        Inventory inv = mc.player.getInventory();
        ItemStack st = inv.getItem(idx);
        if (st.isEmpty()) {
            ctx.report(ReportClass.STATUS, "drop: " + ItemInfo.label(idx) + " is empty");
            return StepResult.FAILED;
        }
        what = Crafting.describeItem(st);
        int sel = InventoryCompat.getSelected(inv);
        phase = 1;
        if (idx == sel) {
            return dropTick(ctx, mc); // already in hand -- start dropping this tick
        }
        if (idx <= 8) {
            Crafting.hold(mc, Integer.toString(idx));
            return StepResult.RUNNING; // selection syncs; drop next tick
        }
        // Main inv / armor / offhand: SWAP the source menu slot with the selected hotbar slot.
        // InventoryMenu slot map: 5-8 armor (head,chest,legs,feet), 9-35 main, 36-44 hotbar, 45 offhand.
        int menuSlot;
        switch (idx) {
            case 36: menuSlot = 8;  break; // feet
            case 37: menuSlot = 7;  break; // legs
            case 38: menuSlot = 6;  break; // chest
            case 39: menuSlot = 5;  break; // head
            case 40: menuSlot = 45; break; // offhand
            default: menuSlot = idx; break; // main inv 9-35 maps 1:1
        }
        ContainerCompat.click(mc, mc.player.inventoryMenu.containerId, menuSlot, sel, ContainerCompat.Mode.SWAP);
        return StepResult.RUNNING;
    }

    /** Phase 1: throw -- the whole stack at once (ALL), or one item per tick up to count. */
    private StepResult dropTick(ActionContext ctx, Minecraft mc) {
        if (mc.player.getMainHandItem().isEmpty()) {
            if (dropped > 0) {
                ctx.report(ReportClass.STATUS, "drop: done -- dropped " + dropped + " of " + what + " (stack ran out)");
                return StepResult.DONE;
            }
            ctx.report(ReportClass.STATUS, "drop: nothing arrived in hand (swap failed?) -- held is empty");
            return StepResult.FAILED;
        }
        if (count == ALL) {
            boolean ok = mc.player.drop(true);
            ctx.report(ReportClass.STATUS, ok ? "drop: threw the whole stack of " + what
                    : "drop: nothing to drop (held " + what + ")");
            return ok ? StepResult.DONE : StepResult.FAILED;
        }
        if (mc.player.drop(false)) {
            dropped++;
        }
        if (dropped >= count || mc.player.getMainHandItem().isEmpty()) {
            ctx.report(ReportClass.STATUS, "drop: dropped " + dropped + " of " + what);
            return StepResult.DONE;
        }
        return StepResult.RUNNING;
    }
}
