package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Combat helpers: weapon selection (auto-equip the best thing in the hotbar -- Master's 702b
 * coaching: "you need to switch to a weapon"), ammo checks, and bow ballistics.
 *
 * Values pinned from the 26.1.2 deobf (2026-07-02): arrow speed 3.0 blocks/tick at full charge
 * (20t draw), gravity 0.05/t, drag 0.99/t; spear = PIERCING_WEAPON component (stab, reach
 * 2.0-4.5, full-charge-only) + KINETIC_WEAPON brace (mounted/moving); shield raise delay 5t,
 * axe disables shields 100t. Reference: knowledge note in m1-combat.md / mod-version-gates.
 */
final class CombatOps {

    private CombatOps() {}

    /** DPS-flavored score for a melee weapon; 0 = not a weapon. */
    static double meleeScore(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        double tier = id.startsWith("netherite_") ? 7 : id.startsWith("diamond_") ? 6
                : id.startsWith("iron_") ? 5 : id.startsWith("copper_") ? 4
                : id.startsWith("stone_") ? 3 : id.startsWith("golden_") ? 2
                : id.startsWith("wooden_") ? 1 : 0;
        if (id.endsWith("_sword")) {
            return 60 + tier * 4;          // best sustained DPS + sweep
        }
        if (id.equals("trident")) {
            return 70;                     // 8 dmg melee -- between iron and diamond sword
        }
        if (id.endsWith("_axe") && !id.contains("pickaxe")) {
            return 45 + tier * 4;          // burst + shield-break, slower
        }
        if (id.equals("mace")) {
            return 50;
        }
        if (id.endsWith("_spear")) {
            return 25 + tier * 3;          // low base dmg, but 4.5 reach (band tactics)
        }
        if (id.endsWith("_pickaxe")) {
            return 10 + tier;
        }
        if (id.endsWith("_shovel")) {
            return 6 + tier;
        }
        return 0;
    }

    /** Hotbar slot (0-8) with the best melee weapon, or -1 if nothing scores. */
    static int bestMeleeSlot(LocalPlayer p) {
        return bestMeleeSlot(p, false);
    }

    /** Best melee slot, optionally excluding spears (their 2.0 minimum reach fails point-blank). */
    static int bestMeleeSlot(LocalPlayer p, boolean excludeSpear) {
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i <= 8; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (excludeSpear && s.has(DataComponents.PIERCING_WEAPON)) {
                continue;
            }
            double sc = meleeScore(s);
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        return best;
    }

    /** Hotbar slot holding a bow, or -1. */
    static int bowSlot(LocalPlayer p) {
        for (int i = 0; i <= 8; i++) {
            if (p.getInventory().getItem(i).is(Items.BOW)) {
                return i;
            }
        }
        return -1;
    }

    /** Total arrows anywhere in the main inventory + hotbar. */
    static int arrowCount(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("arrow")) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** True if the held main-hand item is a spear (component-driven, version-honest). */
    static boolean holdingSpear(LocalPlayer p) {
        return p.getMainHandItem().has(DataComponents.PIERCING_WEAPON);
    }

    /** Select hotbar slot via the proven path; true on success. */
    static boolean hold(Minecraft mc, int slot) {
        return Crafting.hold(mc, Integer.toString(slot)).startsWith("OK");
    }

    /**
     * Bow pitch (MC degrees, negative = up) to hit a point at horizontal distance {@code h} and
     * height delta {@code dy}, at full charge. Coarse ballistic simulation with the pinned
     * constants (v0=3.0/t, drag 0.99, gravity 0.05); picks the flattest angle within 0.5 blocks.
     */
    static float bowPitchFor(double h, double dy) {
        double bestErr = Double.MAX_VALUE;
        double bestTheta = 0;
        for (double theta = -10; theta <= 45; theta += 0.5) {
            double rad = Math.toRadians(theta);
            double vx = 3.0 * Math.cos(rad);
            double vy = 3.0 * Math.sin(rad);
            double x = 0;
            double y = 0;
            for (int t = 0; t < 100 && x < h; t++) {
                x += vx;
                y += vy;
                vx *= 0.99;
                vy = vy * 0.99 - 0.05;
            }
            double err = Math.abs(y - dy);
            if (err < bestErr) {
                bestErr = err;
                bestTheta = theta;
            }
            if (err < 0.5) {
                break; // flattest sufficient angle wins (shortest flight time)
            }
        }
        return (float) -bestTheta;
    }

    /** Estimated arrow flight ticks to horizontal distance h (for target leading). */
    static int bowFlightTicks(double h) {
        double vx = 3.0;
        double x = 0;
        for (int t = 1; t <= 100; t++) {
            x += vx;
            vx *= 0.99;
            if (x >= h) {
                return t;
            }
        }
        return 100;
    }
}
