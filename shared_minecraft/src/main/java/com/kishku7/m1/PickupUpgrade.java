package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto armor-upgrade. Every ~5s, with no GUI open, it scans the player's hotbar + main inventory and
 * equips the best available armor per slot per the tree:
 *   nothing < gold(bare-only) < leather < copper < chainmail < iron < diamond < netherite
 * Ties (same material) break on more remaining durability. Gold = value 0.5: it beats nothing but
 * loses to any real tier, and is replaced as soon as something better is picked up.
 *
 * Equipping rides the container-click protocol on the always-open player inventory menu (container 0)
 * -- no screen shown, server-validated, no desync. Each swap is queued and flushed onto the next
 * socket reply by M1Server (prefixed "[auto-upgrade]"); `upgrades` polls them on demand.
 */
public final class PickupUpgrade {

    private static final int PERIOD_TICKS = 100; // ~5s

    private static volatile boolean enabled = true;
    private static int ticks;
    private static final List<String> reports = new ArrayList<>();

    private static final String[] SLOT_NAME = {"HEAD", "CHEST", "LEGS", "FEET"};
    private static final int[] INV_ARMOR  = {39, 38, 37, 36}; // player-inventory index of each armor slot
    private static final int[] MENU_ARMOR = {5, 6, 7, 8};     // InventoryMenu slot of each armor slot
    private static final String[] SUFFIX  = {"_helmet", "_chestplate", "_leggings", "_boots"};

    private PickupUpgrade() {}

    public static synchronized void setEnabled(boolean on) { enabled = on; }
    public static synchronized boolean isEnabled() { return enabled; }

    public static synchronized String drainReports() {
        if (reports.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String r : reports) b.append("[auto-upgrade] ").append(r).append("\n");
        reports.clear();
        if (b.length() > 0 && b.charAt(b.length() - 1) == '\n') b.setLength(b.length() - 1);
        return b.toString();
    }

    /** Registered on ClientTickEvents.END_CLIENT_TICK. */
    public static synchronized void tick(Minecraft mc) {
        if (!enabled) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (M1Compat.screen(mc) != null) return;          // do not fight an open menu
        if (++ticks < PERIOD_TICKS) return;
        ticks = 0;
        for (int s = 0; s < 4; s++) tryUpgradeSlot(mc, s);
    }

    private static void tryUpgradeSlot(Minecraft mc, int s) {
        LocalPlayer p = mc.player;
        Inventory inv = p.getInventory();
        ItemStack current = inv.getItem(INV_ARMOR[s]);
        double bestVal = armorValue(current, s);
        int bestDur = remaining(current);
        int bestInvIdx = -1;
        ItemStack bestStack = ItemStack.EMPTY;

        for (int i = 0; i <= 35; i++) {           // hotbar 0-8 + main 9-35
            ItemStack it = inv.getItem(i);
            double v = armorValue(it, s);
            if (v <= 0) continue;
            int dur = remaining(it);
            if (v > bestVal || (v == bestVal && dur > bestDur)) {
                bestVal = v; bestDur = dur; bestInvIdx = i; bestStack = it;
            }
        }
        if (bestInvIdx < 0) return;               // nothing better in inventory

        int srcMenu = (bestInvIdx <= 8) ? 36 + bestInvIdx : bestInvIdx;
        int cid = p.containerMenu.containerId;    // 0 = player inventory menu (always open)
        String newId = itemPath(bestStack);
        String oldId = current.isEmpty() ? "nothing" : itemPath(current);
        click(mc, cid, srcMenu, 0, ContainerCompat.Mode.PICKUP);          // cursor = new piece
        click(mc, cid, MENU_ARMOR[s], 0, ContainerCompat.Mode.PICKUP);    // new -> armor slot, cursor = old
        click(mc, cid, srcMenu, 0, ContainerCompat.Mode.PICKUP);          // old -> src (no-op if slot was bare)
        reports.add(SLOT_NAME[s] + ": " + oldId + " -> " + newId);
    }

    private static void click(Minecraft mc, int cid, int slot, int btn, ContainerCompat.Mode mode) {
        ContainerCompat.click(mc, cid, slot, btn, mode);
    }

    // value: nothing 0; gold 0.5; leather 1; copper 2; chainmail 3; iron 4; diamond 5; netherite 6.
    // 0 if the stack is not armor for slot s, or an unrecognized/special material.
    private static double armorValue(ItemStack it, int s) {
        if (it.isEmpty()) return 0;
        String id = itemPath(it);
        if (!id.endsWith(SUFFIX[s])) return 0;
        if (id.startsWith("turtle") || id.startsWith("wolf") || id.startsWith("armadillo")) return 0;
        if (id.startsWith("netherite")) return 6;
        if (id.startsWith("diamond")) return 5;
        if (id.startsWith("iron")) return 4;
        if (id.startsWith("chainmail")) return 3;
        if (id.startsWith("copper")) return 2;
        if (id.startsWith("leather")) return 1;
        if (id.startsWith("golden")) return 0.5;
        return 0;
    }

    private static int remaining(ItemStack it) {
        if (it.isEmpty() || !it.isDamageableItem()) return Integer.MAX_VALUE;
        return it.getMaxDamage() - it.getDamageValue();
    }

    private static String itemPath(ItemStack it) {
        return BuiltInRegistries.ITEM.getKey(it.getItem()).getPath();
    }
}
