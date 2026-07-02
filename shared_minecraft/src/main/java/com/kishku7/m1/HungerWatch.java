package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-eat (Kishku7, 2026-07-01). Registered on END_CLIENT_TICK alongside the other watchers.
 *
 * <p>Trigger: food &lt;= 18 (2 below max). While sprint-chasing (follow catch-up engaged) eating is
 * DEFERRED unless food is critically low (&lt;= 7, where vanilla kills sprint anyway). Once eating
 * starts it continues until food is back to 20 or no acceptable food remains. Eating happens while
 * moving (the use key is held; MC slows the walk -- physics, accepted).
 *
 * <p>Food choice, by Kishku7's preference order: golden carrot &gt; cooked beef/porkchop &gt; cooked
 * chicken/fish/mutton &gt; bread &gt; any other plain food. Harmful/effect foods are SKIPPED
 * (spider eye, poisonous potato, pufferfish, chorus fruit, suspicious stew) EXCEPT rotten flesh,
 * which is eaten only when it is the ONLY food. Golden apples are reserved for combat emergencies
 * (a defend interrupt is active AND health &lt;= 8) and are preferred then. Hotbar items take
 * precedence over main inventory; a main-inventory pick is SWAPped into the selected hotbar slot
 * first (MC cannot eat from the backpack).
 */
public final class HungerWatch {

    private static final int TRIGGER = 18;      // eat at 2 below max
    private static final int CRITICAL = 7;      // eat even mid-chase at/below this
    private static final int FULL = 20;
    private static final int EMERGENCY_HEALTH = 8;

    /** name (registry path) -> preference score; higher eats first. */
    private static final Map<String, Integer> PREF = new HashMap<>();
    static {
        PREF.put("golden_carrot", 100);
        PREF.put("cooked_beef", 90);
        PREF.put("cooked_porkchop", 90);
        PREF.put("cooked_chicken", 80);
        PREF.put("cooked_cod", 80);
        PREF.put("cooked_salmon", 80);
        PREF.put("cooked_mutton", 80);
        PREF.put("bread", 70);
        // everything else edible falls through to OTHER_FOODS at 50
        PREF.put("rotten_flesh", 1); // ONLY when nothing else remains
    }
    /** Plain safe foods accepted at the default score. */
    private static final String[] OTHER_FOODS = {
        "apple", "baked_potato", "carrot", "melon_slice", "sweet_berries", "glow_berries",
        "cooked_rabbit", "beetroot", "cookie", "pumpkin_pie", "mushroom_stew", "rabbit_stew",
        "beetroot_soup", "dried_kelp", "beef", "porkchop", "chicken", "cod", "salmon", "mutton",
        "rabbit", "potato", "tropical_fish", "honey_bottle"
    };
    private static final String[] EMERGENCY_ONLY = { "golden_apple", "enchanted_golden_apple" };

    private static boolean eating;      // sticky: keep going until FULL or out of food
    private static boolean holdingUse;

    private HungerWatch() {}

    /** Registered on ClientTickEvents.END_CLIENT_TICK. */
    public static synchronized void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { reset(mc); return; }
        if (M1Compat.screen(mc) != null) { releaseUse(mc); return; } // menu open: pause, keep state

        int food = p.getFoodData().getFoodLevel();
        boolean emergency = AgentRuntime.queue().hasInterrupt() && p.getHealth() <= EMERGENCY_HEALTH;

        if (!eating) {
            int threshold = (MoveControl.isSprinting() && !emergency && food > CRITICAL) ? -1 : TRIGGER;
            if (threshold < 0 || food > threshold) return;
            eating = true;
        }
        if (food >= FULL && !emergency) { finish(mc); return; }

        // Already chewing? Keep the use key held until this item finishes.
        if (p.isUsingItem()) { holdUse(mc); return; }

        // Pick the next food (hotbar first, then main inventory).
        Inventory inv = p.getInventory();
        int hotbarSlot = bestSlot(inv, 0, 9, emergency);
        if (hotbarSlot >= 0) {
            InventoryCompat.setSelected(inv, hotbarSlot);
            holdUse(mc);
            return;
        }
        int invSlot = bestSlot(inv, 9, 36, emergency);
        if (invSlot < 0) { finish(mc); return; } // nothing edible left
        // SWAP the pick into the currently selected hotbar slot (player inventory menu id 0;
        // main-inventory index i maps to menu slot i for 9..35).
        int sel = InventoryCompat.getSelected(inv);
        ContainerCompat.click(mc, p.inventoryMenu.containerId, invSlot, sel, ContainerCompat.Mode.SWAP);
        // next tick the hotbar scan finds it and starts eating
    }

    /** Best food slot in inventory range [from, to); -1 when none acceptable. */
    private static int bestSlot(Inventory inv, int from, int to, boolean emergency) {
        int best = -1;
        int bestScore = 0;
        boolean nonRottenExists = false;
        for (int i = from; i < to; i++) {
            int s = score(inv.getItem(i), emergency);
            if (s > 1) nonRottenExists = true;
            if (s > bestScore) { bestScore = s; best = i; }
        }
        // rotten flesh only when it was the only food found in this range
        if (bestScore == 1 && nonRottenExists) return -1;
        return best;
    }

    private static int score(ItemStack st, boolean emergency) {
        if (st == null || st.isEmpty()) return 0;
        String name = itemName(st);
        if (emergency) {
            for (String g : EMERGENCY_ONLY) {
                if (name.equals(g)) return 200; // gapples first in a combat emergency
            }
        } else {
            for (String g : EMERGENCY_ONLY) {
                if (name.equals(g)) return 0;   // reserved otherwise
            }
        }
        Integer pref = PREF.get(name);
        if (pref != null) return pref;
        for (String f : OTHER_FOODS) {
            if (name.equals(f)) return 50;
        }
        return 0; // unknown / harmful / not food
    }

    /** Registry path of the stack's item, e.g. "golden_carrot". */
    private static String itemName(ItemStack st) {
        String s = st.getItem().toString(); // "minecraft:golden_carrot" or "golden_carrot" per version
        int c = s.indexOf(':');
        return (c >= 0) ? s.substring(c + 1) : s;
    }

    private static void holdUse(Minecraft mc) {
        mc.options.keyUse.setDown(true);
        holdingUse = true;
    }

    private static void releaseUse(Minecraft mc) {
        if (holdingUse) {
            mc.options.keyUse.setDown(false);
            holdingUse = false;
        }
    }

    private static void finish(Minecraft mc) {
        eating = false;
        releaseUse(mc);
    }

    private static void reset(Minecraft mc) {
        eating = false;
        if (mc != null && mc.options != null) releaseUse(mc);
    }

    /** One-line status for `where`/debug surfaces. */
    public static synchronized String status() {
        return "hunger: eating=" + eating;
    }
}
