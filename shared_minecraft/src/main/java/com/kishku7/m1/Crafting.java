package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Crafting + inventory interaction, client-side, no mixin. Uses gameMode.handleContainerInput
 * (the slot-click path), useItemOn (placing/opening), and a tick-driven {@link CraftHarvest} to
 * collect results (which appear a tick after ingredients are placed and only move out with an
 * empty cursor).
 *
 * Slot maps (menu slot indices):
 *   result = 0; crafting grid = 1..(w*w) row-major, top-left first; player inventory + hotbar follow.
 *   "below slot N" in a width-w grid = N + w. So vertical pair from the top-left = (1, 1+w).
 *   InventoryMenu = 2x2 (w=2, grid 1..4); CraftingMenu (a placed table) = 3x3 (w=3, grid 1..9).
 *
 * Recipes (grid-aware):
 *   planks: 1 log in slot 1, harvest all (each log -> 4 planks).
 *   table : 1 plank in each of the top-left 2x2 = {1, 2, 1+w, 2+w}.
 *   sticks: 1 plank in the top-left vertical pair = {1, 1+w}.
 *   axe   : 3x3 only - planks at {1,2,4}, sticks at {5,8}.
 * Sticks and the axe are best done in a placed table (3x3), which is fully verified; the bootstrap
 * crafting table itself is made in the 2x2 inventory grid.
 */
public final class Crafting {
    private Crafting() {}

    static AbstractContainerMenu menu(Minecraft mc) { return mc.player.containerMenu; }

    static int containerId(Minecraft mc) { return menu(mc).containerId; }

    static void click(Minecraft mc, int slot, int button, ContainerInput mode) {
        mc.gameMode.handleContainerInput(containerId(mc), slot, button, mode, mc.player);
    }

    static String itemId(ItemStack it) {
        return it.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(it.getItem()).getPath();
    }

    /** A menu slot (index >= minIndex) whose item id contains path; -1 if none. */
    static int findSlot(Minecraft mc, String path, int minIndex) {
        for (Slot sl : menu(mc).slots) {
            if (sl.index >= minIndex && itemId(sl.getItem()).contains(path)) return sl.index;
        }
        return -1;
    }

    /** Crafting grid width of the open menu: 3 for a placed table (CraftingMenu), else 2 (inventory). */
    static int gridWidth(Minecraft mc) {
        return (menu(mc) instanceof CraftingMenu) ? 3 : 2;
    }

    /** First inventory slot index (just past the result + grid) for a given grid width. */
    static int invStart(int w) { return w * w + 1; }

    // ---------- commands ----------

    public static String openInv(Minecraft mc) {
        if (mc.player == null) return "openinv: not in world";
        M1Compat.setScreen(mc, new InventoryScreen(mc.player));
        return "OK inventory open (2x2 grid, containerId=" + containerId(mc) + ")";
    }

    public static String close(Minecraft mc) {
        M1Compat.setScreen(mc, null);
        return "OK closed";
    }

    public static String hold(Minecraft mc, String rest) {
        if (mc.player == null) return "hold: not in world";
        try {
            int n = Integer.parseInt(rest.trim());
            if (n < 0 || n > 8) return "ERR hold 0..8";
            mc.player.getInventory().setSelectedSlot(n);
            return "OK hold hotbar " + n + " = " + describeItem(mc.player.getInventory().getItem(n));
        } catch (Exception e) { return "ERR usage: hold <0-8>"; }
    }

    /** Move an item (id substring) into hotbar slot 0 and select it. Requires an open container. */
    public static String equip(Minecraft mc, String rest) {
        if (mc.player == null || mc.player.containerMenu == null) return "equip: open inventory first";
        String path = rest.trim();
        if (path.isEmpty()) return "ERR usage: equip <item>";
        int src = findSlot(mc, path, 1);
        if (src < 0) return "equip: no " + path + " found";
        click(mc, src, 0, ContainerInput.SWAP); // hotbar slot 0 == swap button 0
        mc.player.getInventory().setSelectedSlot(0);
        return "OK equipped " + path + " to hotbar 0";
    }

    public static String slotCmd(Minecraft mc, String rest) {
        if (mc.player == null || mc.player.containerMenu == null) return "slot: no open container";
        String[] t = rest.trim().split("\\s+");
        try {
            int slot = Integer.parseInt(t[0]);
            int btn = t.length > 1 ? Integer.parseInt(t[1]) : 0;
            ContainerInput mode = ContainerInput.PICKUP;
            if (t.length > 2) {
                String m = t[2].toLowerCase();
                if (m.startsWith("q")) mode = ContainerInput.QUICK_MOVE;
                else if (m.startsWith("sw")) mode = ContainerInput.SWAP;
            }
            click(mc, slot, btn, mode);
            return "OK slot " + slot + " btn " + btn + " " + mode;
        } catch (Exception e) { return "ERR usage: slot <id> [button] [pickup|quick|swap]"; }
    }

    public static String place(Minecraft mc) {
        if (mc.player == null) return "place: not in world";
        if (!(mc.hitResult instanceof BlockHitResult bhr) || mc.hitResult.getType() == HitResult.Type.MISS)
            return "place: not looking at a block (look at the ground / target first)";
        InteractionResult r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, bhr);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return "OK place/use -> " + r;
    }

    public static String craft(Minecraft mc, String rest) {
        if (mc.player == null || mc.player.containerMenu == null)
            return "craft: open a crafting screen first (openinv, or place+open a crafting table)";
        switch (rest.trim().toLowerCase()) {
            case "planks": return planks(mc);
            case "sticks": return sticks(mc);
            case "table":
            case "crafting_table": return table(mc);
            case "axe":
            case "wooden_axe": return axe(mc);
            default: return "ERR craft planks|sticks|table|axe";
        }
    }

    // 1 log -> 4 planks; consume all logs.
    static String planks(Minecraft mc) {
        int log = findSlot(mc, "_log", 1);
        if (log < 0) return "craft planks: no logs in inventory";
        click(mc, log, 0, ContainerInput.PICKUP); // cursor = logs
        click(mc, 1, 0, ContainerInput.PICKUP);    // whole stack into grid slot 1 (cursor now empty)
        CraftHarvest.start();
        return "OK crafting planks (poll 'inv')";
    }

    // 1 plank in the top-left 2x2 -> 1 crafting table.
    static String table(Minecraft mc) {
        int w = gridWidth(mc);
        int p = findSlot(mc, "_planks", invStart(w));
        if (p < 0) return "craft table: no planks";
        click(mc, p, 0, ContainerInput.PICKUP);
        for (int c : new int[]{1, 2, 1 + w, 2 + w}) click(mc, c, 1, ContainerInput.PICKUP);
        click(mc, p, 0, ContainerInput.PICKUP); // deposit leftover cursor (empty hand for harvest)
        CraftHarvest.start();
        return "OK crafting crafting_table (poll 'inv')";
    }

    // 2 planks vertical -> 4 sticks.
    static String sticks(Minecraft mc) {
        int w = gridWidth(mc);
        int p = findSlot(mc, "_planks", invStart(w));
        if (p < 0) return "craft sticks: no planks";
        click(mc, p, 0, ContainerInput.PICKUP);
        click(mc, 1, 1, ContainerInput.PICKUP);
        click(mc, 1 + w, 1, ContainerInput.PICKUP);
        click(mc, p, 0, ContainerInput.PICKUP); // deposit leftover
        CraftHarvest.start();
        return "OK crafting sticks (poll 'inv')";
    }

    // 3x3 (table open): planks at 1,2,4 ; sticks at 5,8 -> wooden axe.
    static String axe(Minecraft mc) {
        if (gridWidth(mc) < 3) return "craft axe: open a crafting table (3x3) first";
        int p = findSlot(mc, "_planks", 10);
        int st = findSlot(mc, "stick", 10);
        if (p < 0 || st < 0) return "craft axe: need planks + sticks in inventory";
        click(mc, p, 0, ContainerInput.PICKUP);
        click(mc, 1, 1, ContainerInput.PICKUP);
        click(mc, 2, 1, ContainerInput.PICKUP);
        click(mc, 4, 1, ContainerInput.PICKUP);
        click(mc, p, 0, ContainerInput.PICKUP); // deposit leftover planks
        click(mc, st, 0, ContainerInput.PICKUP);
        click(mc, 5, 1, ContainerInput.PICKUP);
        click(mc, 8, 1, ContainerInput.PICKUP);
        click(mc, st, 0, ContainerInput.PICKUP); // deposit leftover sticks
        CraftHarvest.start();
        return "OK crafting wooden_axe (poll 'inv')";
    }

    static String describeItem(ItemStack it) {
        return it.isEmpty() ? "empty" : (it.getCount() + "x " + it.getHoverName().getString());
    }
}
