package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code examine} verb: a full, human-and-AI-readable readout of any item the player is
 * carrying -- by slot (hb1-9, inv N, head/chest/legs/feet, offhand, hand) or by name/id search
 * across the whole inventory. Answers "does my sword have mending?" in one call and surfaces
 * everything the vault / crafting layers need: exact registry id, custom name, enchantments,
 * durability, the advanced tooltip, and the non-default component list (the reason an item needs
 * an id#hash vault key instead of its plain id).
 *
 * <p>Slot spaces, made explicit to kill the session-703a hb-vs-index confusion: raw inventory
 * indices are 0-40 (0-8 hotbar, 9-35 main, 36-39 armor feet->head, 40 offhand); hb1-9 is the
 * human hotbar notation (hbN = idx N-1). Every label printed here shows both.
 */
public final class ItemInfo {

    private ItemInfo() {
    }

    /** Resolve a slot spec / name and produce the full readout. Read-only, render thread. */
    public static String examine(Minecraft mc, String rest) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return "examine: not in world";
        }
        Inventory inv = p.getInventory();
        String spec = (rest == null) ? "" : rest.trim();
        int idx = resolve(mc, spec);
        if (idx == -2) {
            return "ERR usage: examine [hand|offhand|hb1-9|inv <0-40>|head|chest|legs|feet|<item name or id>]";
        }
        if (idx < 0) {
            return "examine: nothing matching '" + spec + "' in inventory (searched ids + display names, all 41 slots)";
        }
        ItemStack st = inv.getItem(idx);
        if (st.isEmpty()) {
            return "examine " + label(idx) + ": empty";
        }
        StringBuilder b = new StringBuilder();
        b.append("examine ").append(label(idx)).append(": ")
                .append(st.getCount()).append("x \"").append(st.getHoverName().getString()).append("\"\n");
        b.append("id: ").append(BuiltInRegistries.ITEM.getKey(st.getItem())).append("\n");

        Component custom = M1Compat.customName(st);
        if (custom != null) {
            b.append("custom name: \"").append(custom.getString()).append("\"\n");
        }

        // Enchantments (worn/held gear) + stored enchantments (books).
        String en = M1Compat.enchantmentsLine(st, false);
        String stored = M1Compat.enchantmentsLine(st, true);
        if (!en.isEmpty()) {
            b.append("enchantments: ").append(en).append("\n");
        }
        if (!stored.isEmpty()) {
            b.append("stored enchantments (book): ").append(stored).append("\n");
        }
        if (en.isEmpty() && stored.isEmpty() && st.getMaxDamage() > 0) {
            b.append("enchantments: none\n");
        }

        if (st.getMaxDamage() > 0) {
            int left = st.getMaxDamage() - st.getDamageValue();
            b.append("durability: ").append(left).append("/").append(st.getMaxDamage())
                    .append(st.isDamaged() ? " (" + st.getDamageValue() + " used)" : " (undamaged)").append("\n");
        }

        // Vault key guidance: default-component items vault under their plain id; anything with a
        // component patch (custom name, enchants, damage, ...) vaults under id#hash -- the exact
        // key comes from the vault itself.
        if (!M1Compat.hasComponentPatch(st)) {
            b.append("vault key: ").append(Crafting.itemId(st)).append(" (plain -- no custom components)\n");
        } else {
            b.append("vault key: ").append(Crafting.itemId(st))
                    .append("#<hash> (component-bearing; get the exact key from 'vault find ")
                    .append(Crafting.itemId(st)).append("' or the AMBIG list on withdraw)\n");
        }

        // Advanced tooltip -- everything the player would see, plus ids/durability.
        try {
            List<Component> tip = M1Compat.tooltipLines(mc, p, st);
            if (!tip.isEmpty()) {
                b.append("tooltip:\n");
                for (Component c : tip) {
                    String s = c.getString();
                    if (!s.isBlank()) {
                        b.append("  ").append(s).append("\n");
                    }
                }
            }
        } catch (Throwable t) {
            b.append("tooltip: (unavailable: ").append(t.getClass().getSimpleName()).append(")\n");
        }

        // NON-DEFAULT components only (the patch) -- exactly WHY an item is special. The full
        // prototype map is noise (a plain sword carries ~18 default components).
        List<String> comps = M1Compat.componentPatchList(st);
        if (!comps.isEmpty()) {
            b.append("non-default components: ").append(String.join(", ", comps)).append("\n");
        }

        b.setLength(b.length() - 1); // trailing newline
        return b.toString();
    }


    /** Human label showing BOTH slot spaces. */
    static String label(int idx) {
        if (idx >= 0 && idx <= 8) {
            return "hb" + (idx + 1) + " (idx " + idx + ")";
        }
        if (idx >= 9 && idx <= 35) {
            return "inv " + idx;
        }
        switch (idx) {
            case 36: return "feet (idx 36)";
            case 37: return "legs (idx 37)";
            case 38: return "chest (idx 38)";
            case 39: return "head (idx 39)";
            case 40: return "offhand (idx 40)";
            default: return "idx " + idx;
        }
    }

    /**
     * Spec -> raw inventory index. -1 = name searched but not found; -2 = unusable spec.
     * Accepts: "" / hand, offhand, head|chest|legs|feet, hbN, "inv N", bare int (raw idx),
     * anything else = name/id substring search (case/space-blind) across idx 0-40.
     */
    static int resolve(Minecraft mc, String spec) {
        LocalPlayer p = mc.player;
        Inventory inv = p.getInventory();
        String s = spec.toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty() || s.equals("hand") || s.equals("held")) {
            return InventoryCompat.getSelected(inv);
        }
        switch (s) {
            case "offhand": return 40;
            case "head":    return 39;
            case "chest":   return 38;
            case "legs":    return 37;
            case "feet":    return 36;
            default: break;
        }
        if (s.startsWith("hb")) {
            try {
                int n = Integer.parseInt(s.substring(2));
                return (n >= 1 && n <= 9) ? n - 1 : -2;
            } catch (NumberFormatException e) {
                return -2;
            }
        }
        String num = s.startsWith("inv") ? s.substring(3).trim() : s;
        try {
            int n = Integer.parseInt(num);
            return (n >= 0 && n <= 40) ? n : -2;
        } catch (NumberFormatException ignored) {
            // fall through to name search
        }
        String q = s.replace(" ", "").replace("_", "");
        for (int i = 0; i <= 40; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) {
                continue;
            }
            String id = Crafting.itemId(st).replace("_", "");
            String name = st.getHoverName().getString().toLowerCase(Locale.ROOT).replace(" ", "");
            if (id.contains(q) || name.contains(q)) {
                return i;
            }
        }
        return -1;
    }
}
