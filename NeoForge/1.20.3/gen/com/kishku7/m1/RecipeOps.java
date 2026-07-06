package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generic crafting driven by the LIVE recipe book -- no hardcoded recipe list. The synced recipe
 * set is the client's authoritative "what can be made" catalog (vanilla + datapack + modded for
 * free); {@code canCraft} answers feasibility against the player's real inventory, and
 * {@link RecipeCompat#placeRecipe} does the authoritative server-side grid fill.
 *
 * All recipe-book access goes through {@link RecipeCompat}, which bridges the 1.21.2 recipe rewrite
 * (RecipeDisplayEntry 1.21.2+ vs RecipeHolder pre-1.21.2). Entry handles are opaque {@code Object}.
 */
public final class RecipeOps {

    private RecipeOps() {
    }

    /** All recipe-book entry handles (opaque; RecipeDisplayEntry or RecipeHolder per version). */
    static List<Object> entries(Minecraft mc) {
        return RecipeCompat.entries(mc);
    }

    /** Result item id ("namespace:path") of an entry, or "" when unresolvable. */
    static String resultId(Minecraft mc, Object e) {
        return RecipeCompat.resultId(mc, e);
    }

    /** Entries whose result id matches 'item' (exact path or full id first, else substring). */
    static List<Object> producersOf(Minecraft mc, String item) {
        String want = item.toLowerCase(Locale.ROOT).trim();
        List<Object> exact = new ArrayList<>();
        List<Object> partial = new ArrayList<>();
        for (Object e : entries(mc)) {
            String id = resultId(mc, e);
            if (id.isEmpty()) {
                continue;
            }
            String path = id.substring(id.indexOf(':') + 1);
            if (id.equals(want) || path.equals(want)) {
                exact.add(e);
            } else if (path.contains(want)) {
                partial.add(e);
            }
        }
        return exact.isEmpty() ? partial : exact;
    }

    /** Human summary of one ingredient: first acceptable item path (+alts). */
    static String ingredientLabel(Ingredient ing) {
        List<String> ids = RecipeCompat.ingredientItemIds(ing);
        if (ids.isEmpty()) {
            return "?";
        }
        String first = ids.get(0);
        String path = first.substring(first.indexOf(':') + 1);
        return ids.size() > 1 ? path + "(+alts)" : path;
    }

    /** Does the player hold something this ingredient accepts? */
    static boolean holdsIngredient(Minecraft mc, Ingredient ing) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && ing.test(st)) {
                return true;
            }
        }
        return false;
    }

    /** Is anything this ingredient accepts remembered in vault storage (last-seen)? */
    static String vaultSourceFor(Minecraft mc, Ingredient ing) {
        for (String id : RecipeCompat.ingredientItemIds(ing)) {
            if (!StorageMemory.find(mc, id).isEmpty()) {
                return id;
            }
        }
        return null;
    }

    /**
     * Feasibility report for crafting 'item': craftable now, or what is missing -- with
     * "(in vault)" / "(craftable)" annotations per missing ingredient (feasibility-only
     * recursion, depth 1).
     */
    public static String canCraft(Minecraft mc, String item) {
        if (mc.player == null) {
            return "cancraft: not in world";
        }
        if (item.isEmpty()) {
            return "ERR usage: cancraft <item>";
        }
        List<Object> prods = producersOf(mc, item);
        if (prods.isEmpty()) {
            return "cancraft " + item + ": NO recipe known to the recipe book";
        }
        for (Object e : prods) {
            if (RecipeCompat.canCraft(mc, e)) {
                return "cancraft " + item + ": YES via " + resultId(mc, e)
                        + " (" + RecipeCompat.label(e) + ", " + prods.size() + " producer(s))";
            }
        }
        // not craftable -- report the first producer's missing ingredients
        Object best = prods.get(0);
        List<Ingredient> reqs = RecipeCompat.requirements(best);
        if (reqs.isEmpty()) {
            return "cancraft " + item + ": recipe found but its requirements are not synced";
        }
        StringBuilder b = new StringBuilder("cancraft " + item + ": NO -- recipe "
                + resultId(mc, best) + " needs:");
        for (Ingredient ing : reqs) {
            String label = ingredientLabel(ing);
            if (holdsIngredient(mc, ing)) {
                b.append("\n  HAVE ").append(label);
                continue;
            }
            b.append("\n  NEED ").append(label);
            String vaultId = vaultSourceFor(mc, ing);
            if (vaultId != null) {
                b.append(" (in vault: ").append(vaultId).append(')');
            }
            // depth-1 craftability of the first concrete option
            List<String> opts = RecipeCompat.ingredientItemIds(ing);
            if (!opts.isEmpty()) {
                for (Object se : producersOf(mc, opts.get(0))) {
                    if (RecipeCompat.canCraft(mc, se)) {
                        b.append(" (craftable)");
                        break;
                    }
                }
            }
        }
        return b.toString();
    }
}
