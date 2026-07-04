package com.kishku7.m1;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * HIGH-LEVEL inventory verbs (Master directive after 702d: "way too much confusion on some key
 * points" -- the AI was juggling three index spaces and dictated slot surgery). These verbs take
 * NAMES and human slot words, never raw indices; the mod owns the mechanics.
 *
 *   equip all            wear the best armor set + shield to off-hand + best sword to hand
 *   equip <name>         auto-routing: armor -> its armor slot, shield -> off-hand, else to hand
 *   organize hotbar      the standard combat layout (Master doctrine, 702d): hb1 sword, hb2
 *                        pickaxe, hb3 axe, hb4 shovel, hb5 hoe, hb9 best food
 *   takeall              empty the OPEN container into the inventory (quick-move loop)
 *   stash junk           move junk (rotten flesh / bones / spider eyes / poisonous potato) out of
 *                        the hotbar into the main inventory
 *
 * Human slot words (accepted by moveitem): hb1..hb9 (1-based, as spoken), offhand,
 * head|helmet, chest, legs, feet|boots.
 *
 * All player-inventory verbs auto-open the 2x2 inventory if a foreign container is up, and all
 * operate on InventoryMenu ids internally: armor 5-8, main 9-35, hotbar 36-44, offhand 45.
 */
final class InventoryOps {

    private static final int ARMOR_HEAD = 5;
    private static final int MAIN_START = 9;
    private static final int HOTBAR_START = 36;
    private static final int OFFHAND = 45;

    private static final String[] JUNK = {
            "rotten_flesh", "bone", "spider_eye", "poisonous_potato"
    };

    private InventoryOps() {}

    // ---------- equip all ----------

    static String equipAll(Minecraft mc) {
        String prep = ensurePlayerInv(mc);
        if (prep != null) {
            return prep;
        }
        AbstractContainerMenu m = mc.player.containerMenu;
        StringBuilder did = new StringBuilder();

        for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            int armorSlot = armorMenuSlot(es);
            double worn = armorScore(mc, m.getSlot(armorSlot).getItem(), es);
            int best = -1;
            double bestScore = worn;
            for (int i = MAIN_START; i <= OFFHAND; i++) {
                if (i == armorSlot) {
                    continue;
                }
                double sc = armorScore(mc, m.getSlot(i).getItem(), es);
                if (sc > bestScore) {
                    bestScore = sc;
                    best = i;
                }
            }
            if (best >= 0) {
                // quick-move auto-routes armor into its armor slot; if one is worn already,
                // swap via cursor: pickup candidate -> pickup armor slot -> drop old into source
                if (m.getSlot(armorSlot).getItem().isEmpty()) {
                    Crafting.click(mc, best, 0, ContainerCompat.Mode.QUICK_MOVE);
                } else {
                    Crafting.click(mc, best, 0, ContainerCompat.Mode.PICKUP);
                    Crafting.click(mc, armorSlot, 0, ContainerCompat.Mode.PICKUP);
                    Crafting.click(mc, best, 0, ContainerCompat.Mode.PICKUP);
                }
                did.append(es.getName()).append("=")
                        .append(idOf(m.getSlot(armorSlot).getItem())).append(" ");
            }
        }

        // shield -> off-hand
        if (!m.getSlot(OFFHAND).getItem().is(Items.SHIELD)) {
            int sh = findByPredicate(m, s -> s.is(Items.SHIELD));
            if (sh >= 0) {
                Crafting.click(mc, sh, 0, ContainerCompat.Mode.PICKUP);
                Crafting.click(mc, OFFHAND, 0, ContainerCompat.Mode.PICKUP);
                // anything displaced goes back to the shield's source cell
                Crafting.click(mc, sh, 0, ContainerCompat.Mode.PICKUP);
                did.append("offhand=shield ");
            }
        }

        // best weapon -> hb1 + hold it
        int wep = bestByScore(m, CombatOps::meleeScore, HOTBAR_START);
        if (wep >= 0) {
            moveToMenuSlot(mc, m, wep, HOTBAR_START);
            did.append("hb1=").append(idOf(m.getSlot(HOTBAR_START).getItem()));
        }
        Crafting.hold(mc, "0");
        return did.length() == 0
                ? "equip all: nothing better than what is already worn/held"
                : "OK equip all: " + did.toString().trim();
    }

    // ---------- equip <name> (auto-routing) ----------

    static String equipNamed(Minecraft mc, String name) {
        if (name.trim().toLowerCase(Locale.ROOT).contains("backpack")) {
            return BackpackOps.equip(mc); // backpacks wear via the code-level TB path, not a slot click
        }
        String prep = ensurePlayerInv(mc);
        if (prep != null) {
            return prep;
        }
        AbstractContainerMenu m = mc.player.containerMenu;
        int src = Crafting.findSlot(mc, name, 1);
        if (src < 0) {
            return "equip: no '" + name + "' found (id or display name)";
        }
        ItemStack s = m.getSlot(src).getItem();
        EquipmentSlot eq = M1Compat.equipSlot(mc, s);
        if (eq != null && (eq == EquipmentSlot.HEAD || eq == EquipmentSlot.CHEST
                || eq == EquipmentSlot.LEGS || eq == EquipmentSlot.FEET)) {
            int armorSlot = armorMenuSlot(eq);
            if (m.getSlot(armorSlot).getItem().isEmpty()) {
                Crafting.click(mc, src, 0, ContainerCompat.Mode.QUICK_MOVE);
            } else {
                Crafting.click(mc, src, 0, ContainerCompat.Mode.PICKUP);
                Crafting.click(mc, armorSlot, 0, ContainerCompat.Mode.PICKUP);
                Crafting.click(mc, src, 0, ContainerCompat.Mode.PICKUP);
            }
            return "OK equipped " + idOf(m.getSlot(armorSlot).getItem()) + " to " + eq.getName();
        }
        if (s.is(Items.SHIELD)) {
            Crafting.click(mc, src, 0, ContainerCompat.Mode.PICKUP);
            Crafting.click(mc, OFFHAND, 0, ContainerCompat.Mode.PICKUP);
            Crafting.click(mc, src, 0, ContainerCompat.Mode.PICKUP);
            return "OK equipped shield to off-hand";
        }
        // TRINKET routing (backpacks etc, 702d): any trinket slot whose validator accepts the
        // item. Works at menu level -- no hover-to-reveal UI dance needed.
        for (Slot sl : m.slots) {
            if (sl.container == mc.player.getInventory()) {
                continue;
            }
            String cn = sl.container.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String scn = sl.getClass().getName().toLowerCase(Locale.ROOT);
            if (!cn.contains("trinket") && !scn.contains("trinket")) {
                continue;
            }
            if (!sl.getItem().isEmpty() || !sl.mayPlace(s)) {
                continue;
            }
            Crafting.click(mc, src, 0, ContainerCompat.Mode.PICKUP);
            Crafting.click(mc, sl.index, 0, ContainerCompat.Mode.PICKUP);
            if (!m.getCarried().isEmpty()) {
                Crafting.click(mc, src, 0, ContainerCompat.Mode.PICKUP);
            }
            if (!sl.getItem().isEmpty()) {
                return "OK equipped " + idOf(sl.getItem()) + " to trinket slot " + sl.index;
            }
        }
        moveToMenuSlot(mc, m, src, HOTBAR_START);
        Crafting.hold(mc, "0");
        return "OK holding " + idOf(mc.player.getMainHandItem()) + " (hb1)";
    }

    // ---------- organize hotbar ----------

    static String organizeHotbar(Minecraft mc) {
        String prep = ensurePlayerInv(mc);
        if (prep != null) {
            return prep;
        }
        AbstractContainerMenu m = mc.player.containerMenu;
        StringBuilder did = new StringBuilder("OK hotbar: ");
        // Master's standard layout (702d doctrine)
        place(mc, m, did, "hb1", HOTBAR_START, s -> CombatOps.meleeScore(s) >= 45, CombatOps::meleeScore);
        place(mc, m, did, "hb2", HOTBAR_START + 1, s -> idOf(s).endsWith("_pickaxe"), InventoryOps::tierScore);
        place(mc, m, did, "hb3", HOTBAR_START + 2, s -> idOf(s).endsWith("_axe") && !idOf(s).contains("pickaxe"), InventoryOps::tierScore);
        place(mc, m, did, "hb4", HOTBAR_START + 3, s -> idOf(s).endsWith("_shovel"), InventoryOps::tierScore);
        place(mc, m, did, "hb5", HOTBAR_START + 4, s -> idOf(s).endsWith("_hoe"), InventoryOps::tierScore);
        place(mc, m, did, "hb9", HOTBAR_START + 8, s -> foodScore(s) > 0, InventoryOps::foodScore);
        Crafting.hold(mc, "0");
        return did.toString().trim();
    }

    private interface Scorer {
        double score(ItemStack s);
    }

    private static void place(Minecraft mc, AbstractContainerMenu m, StringBuilder did,
            String label, int target, java.util.function.Predicate<ItemStack> want, Scorer scorer) {
        ItemStack cur = m.getSlot(target).getItem();
        if (!cur.isEmpty() && want.test(cur)) {
            double curScore = scorer.score(cur);
            boolean betterExists = false;
            for (int i = MAIN_START; i <= OFFHAND; i++) {
                ItemStack s = m.getSlot(i).getItem();
                if (i != target && want.test(s) && scorer.score(s) > curScore) {
                    betterExists = true;
                    break;
                }
            }
            if (!betterExists) {
                did.append(label).append("=").append(idOf(cur)).append(" ");
                return; // already right
            }
        }
        int best = -1;
        double bestScore = 0;
        for (int i = MAIN_START; i <= OFFHAND; i++) {
            if (i == target) {
                continue;
            }
            ItemStack s = m.getSlot(i).getItem();
            if (s.isEmpty() || !want.test(s)) {
                continue;
            }
            double sc = scorer.score(s);
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        if (best < 0) {
            return; // nothing suitable owned
        }
        moveToMenuSlot(mc, m, best, target);
        did.append(label).append("=").append(idOf(m.getSlot(target).getItem())).append(" ");
    }

    // ---------- takeall ----------

    static String takeAll(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return "takeall: not in world";
        }
        AbstractContainerMenu m = p.containerMenu;
        if (m == p.inventoryMenu) {
            return "takeall: no container open (open a chest/vault first)";
        }
        int moved = 0;
        int stuck = 0;
        for (Slot sl : m.slots) {
            if (sl.container == p.getInventory() || sl.getItem().isEmpty()) {
                continue;
            }
            Crafting.click(mc, sl.index, 0, ContainerCompat.Mode.QUICK_MOVE);
            if (sl.getItem().isEmpty()) {
                moved++;
            } else {
                stuck++;
            }
        }
        return "OK takeall: " + moved + " stack(s) taken"
                + (stuck > 0 ? ", " + stuck + " left (inventory full?)" : "");
    }

    // ---------- stash junk ----------

    static String stashJunk(Minecraft mc) {
        String prep = ensurePlayerInv(mc);
        if (prep != null) {
            return prep;
        }
        AbstractContainerMenu m = mc.player.containerMenu;
        int moved = 0;
        StringBuilder what = new StringBuilder();
        for (int i = HOTBAR_START; i <= OFFHAND; i++) {
            ItemStack s = m.getSlot(i).getItem();
            if (s.isEmpty() || !isJunk(s)) {
                continue;
            }
            String id = idOf(s);
            Crafting.click(mc, i, 0, ContainerCompat.Mode.QUICK_MOVE);
            if (m.getSlot(i).getItem().isEmpty()) {
                moved++;
                what.append(id).append(" ");
            }
        }
        return moved == 0 ? "OK stash: no junk in the hotbar"
                : "OK stash: moved " + moved + " junk stack(s) off the hotbar (" + what.toString().trim() + ")";
    }

    static boolean isJunk(ItemStack s) {
        String id = idOf(s);
        for (String j : JUNK) {
            if (id.equals(j)) {
                return true;
            }
        }
        return false;
    }

    // ---------- moveitem <name|slot> to <human slot> ----------

    static String moveItem(Minecraft mc, String args) {
        String prep = ensurePlayerInv(mc);
        if (prep != null) {
            return prep;
        }
        String[] t = args.split("\\s+to\\s+");
        if (t.length != 2) {
            return "ERR usage: moveitem <item name> to <hb1..hb9|offhand|head|chest|legs|feet>";
        }
        int target = humanSlot(t[1].trim());
        if (target < 0) {
            return "ERR unknown slot '" + t[1].trim() + "' (hb1..hb9, offhand, head, chest, legs, feet)";
        }
        int src = Crafting.findSlot(mc, t[0].trim(), 1);
        if (src < 0) {
            return "moveitem: no '" + t[0].trim() + "' found";
        }
        if (src == target) {
            return "OK already there";
        }
        moveToMenuSlot(mc, mc.player.containerMenu, src, target);
        return "OK moved " + idOf(mc.player.containerMenu.getSlot(target).getItem())
                + " to " + t[1].trim();
    }

    /** hb1..hb9 (1-based!), offhand, head/helmet, chest, legs, feet/boots -> InventoryMenu id. */
    static int humanSlot(String w) {
        String s = w.toLowerCase(Locale.ROOT);
        if (s.matches("hb[1-9]")) {
            return HOTBAR_START + (s.charAt(2) - '1');
        }
        switch (s) {
            case "offhand": return OFFHAND;
            case "head": case "helmet": return ARMOR_HEAD;
            case "chest": case "chestplate": return ARMOR_HEAD + 1;
            case "legs": case "leggings": return ARMOR_HEAD + 2;
            case "feet": case "boots": return ARMOR_HEAD + 3;
            default: return -1;
        }
    }

    // ---------- shared mechanics ----------

    /** Cursor-swap src into target (both InventoryMenu ids), returning any displaced item to src. */
    private static void moveToMenuSlot(Minecraft mc, AbstractContainerMenu m, int src, int target) {
        Crafting.click(mc, src, 0, ContainerCompat.Mode.PICKUP);
        Crafting.click(mc, target, 0, ContainerCompat.Mode.PICKUP);
        if (!m.getCarried().isEmpty()) {
            Crafting.click(mc, src, 0, ContainerCompat.Mode.PICKUP);
        }
    }

    /** Auto-open the player inventory when a foreign container is up. Null = ready; else error. */
    private static String ensurePlayerInv(Minecraft mc) {
        if (mc.player == null) {
            return "ERR not in world";
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            Crafting.openInv(mc);
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            return "ERR could not switch to the player inventory (close the open screen first)";
        }
        return null;
    }

    private static int armorMenuSlot(EquipmentSlot es) {
        switch (es) {
            case HEAD: return ARMOR_HEAD;
            case CHEST: return ARMOR_HEAD + 1;
            case LEGS: return ARMOR_HEAD + 2;
            case FEET: return ARMOR_HEAD + 3;
            default: return -1;
        }
    }

    private static double armorScore(Minecraft mc, ItemStack s, EquipmentSlot es) {
        if (s.isEmpty()) {
            return 0;
        }
        EquipmentSlot eq = M1Compat.equipSlot(mc, s);
        if (eq == null || eq != es) {
            return 0;
        }
        return 1 + tierScore(s);
    }

    private static double tierScore(ItemStack s) {
        String id = idOf(s);
        if (id.startsWith("netherite_")) return 7;
        if (id.startsWith("diamond_")) return 6;
        if (id.startsWith("iron_")) return 5;
        if (id.startsWith("copper_")) return 4;
        if (id.startsWith("chainmail_")) return 3;
        if (id.startsWith("turtle_") || id.startsWith("stone_")) return 2.5;
        if (id.startsWith("golden_")) return 2;
        if (id.startsWith("leather_") || id.startsWith("wooden_")) return 1;
        return 0.5;
    }

    private static double foodScore(ItemStack s) {
        if (s.isEmpty()) {
            return 0;
        }
        if (isJunk(s)) {
            return 0; // junk food (rotten flesh, spider eye) never counts as "best food"
        }
        return M1Compat.foodValue(s);
    }

    private static int findByPredicate(AbstractContainerMenu m, java.util.function.Predicate<ItemStack> p) {
        for (int i = MAIN_START; i <= OFFHAND; i++) {
            if (p.test(m.getSlot(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private static int bestByScore(AbstractContainerMenu m, Scorer scorer, int skipSlot) {
        int best = -1;
        double bestScore = 0;
        for (int i = MAIN_START; i <= OFFHAND; i++) {
            if (i == skipSlot) {
                continue;
            }
            double sc = scorer.score(m.getSlot(i).getItem());
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        return best;
    }

    private static String idOf(ItemStack s) {
        return s.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    /** Unused-import guard: List/ArrayList reserved for the junk-list config expansion. */
    static List<String> junkList() {
        return new ArrayList<>(List.of(JUNK));
    }
}
