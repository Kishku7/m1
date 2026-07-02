package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generic crafting driven by the LIVE recipe book -- no hardcoded recipe list. The synced
 * {@link RecipeDisplayEntry} set is the client's authoritative "what can be made" catalog
 * (vanilla + datapack + modded recipes all included for free), {@code canCraft} answers
 * feasibility against the player's actual inventory, and
 * {@code MultiPlayerGameMode.handlePlaceRecipe} does the authoritative server-side grid fill
 * (the recipe-book click a player makes). 26.x API (RecipeDisplayEntry model).
 *
 * NOTE (RULE 3, features first): this class uses 26-only recipe APIs DIRECTLY. Pre-26 cells will
 * not compile it -- the Cog/facade port happens at the 1.0 port pass, per m1.md PROJECT RULE 3.
 */
public final class RecipeOps {

    private RecipeOps() {
    }

    @SuppressWarnings("deprecation") // Ingredient.items() is deprecated on 26.x with NO public
    // replacement (the backing HolderSet 'values' is private). Sole accessor, kept in one place.
    static java.util.stream.Stream<Holder<Item>> ingredientItems(Ingredient ing) {
        return ing.items();
    }

    /** All recipe-book entries (every collection flattened). */
    static List<RecipeDisplayEntry> entries(Minecraft mc) {
        List<RecipeDisplayEntry> out = new ArrayList<>();
        for (RecipeCollection c : mc.player.getRecipeBook().getCollections()) {
            out.addAll(c.getRecipes());
        }
        return out;
    }

    /** Result item id ("namespace:path") of an entry, or "" when unresolvable. */
    static String resultId(Minecraft mc, RecipeDisplayEntry e) {
        try {
            ContextMap ctx = SlotDisplayContext.fromLevel(mc.level);
            List<ItemStack> rs = e.resultItems(ctx);
            if (rs.isEmpty() || rs.get(0).isEmpty()) {
                return "";
            }
            return BuiltInRegistries.ITEM.getKey(rs.get(0).getItem()).toString();
        } catch (Exception ex) {
            return "";
        }
    }

    /** Entries whose result id matches 'item' (exact path or full id first, else substring). */
    static List<RecipeDisplayEntry> producersOf(Minecraft mc, String item) {
        String want = item.toLowerCase(Locale.ROOT).trim();
        List<RecipeDisplayEntry> exact = new ArrayList<>();
        List<RecipeDisplayEntry> partial = new ArrayList<>();
        for (RecipeDisplayEntry e : entries(mc)) {
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

    static StackedItemContents playerContents(Minecraft mc) {
        StackedItemContents sic = new StackedItemContents();
        mc.player.getInventory().fillStackedContents(sic);
        return sic;
    }

    /** Human summary of one ingredient: first acceptable item path (+N alternatives). */
    static String ingredientLabel(Ingredient ing) {
        List<String> ids = new ArrayList<>();
        ingredientItems(ing).limit(4).forEach((Holder<Item> h) ->
                ids.add(BuiltInRegistries.ITEM.getKey(h.value()).getPath()));
        if (ids.isEmpty()) {
            return "?";
        }
        String first = ids.get(0);
        return ids.size() > 1 ? first + "(+alts)" : first;
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
        List<String> found = new ArrayList<>();
        ingredientItems(ing).limit(12).forEach((Holder<Item> h) -> {
            if (!found.isEmpty()) {
                return;
            }
            String id = BuiltInRegistries.ITEM.getKey(h.value()).toString();
            if (!StorageMemory.find(mc, id).isEmpty()) {
                found.add(id);
            }
        });
        return found.isEmpty() ? null : found.get(0);
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
        List<RecipeDisplayEntry> prods = producersOf(mc, item);
        if (prods.isEmpty()) {
            return "cancraft " + item + ": NO recipe known to the recipe book";
        }
        StackedItemContents sic = playerContents(mc);
        for (RecipeDisplayEntry e : prods) {
            if (e.canCraft(sic)) {
                return "cancraft " + item + ": YES via " + resultId(mc, e)
                        + " (recipe " + e.id().index() + ", " + prods.size() + " producer(s))";
            }
        }
        // not craftable -- report the first producer's missing ingredients
        RecipeDisplayEntry best = prods.get(0);
        if (best.craftingRequirements().isEmpty()) {
            return "cancraft " + item + ": recipe found but its requirements are not synced";
        }
        StringBuilder b = new StringBuilder("cancraft " + item + ": NO -- recipe "
                + resultId(mc, best) + " needs:");
        for (Ingredient ing : best.craftingRequirements().get()) {
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
            List<Holder<Item>> one = new ArrayList<>();
            ingredientItems(ing).limit(1).forEach(one::add);
            if (!one.isEmpty()) {
                String subId = BuiltInRegistries.ITEM.getKey(one.get(0).value()).toString();
                for (RecipeDisplayEntry se : producersOf(mc, subId)) {
                    if (se.canCraft(sic)) {
                        b.append(" (craftable)");
                        break;
                    }
                }
            }
        }
        return b.toString();
    }
}
