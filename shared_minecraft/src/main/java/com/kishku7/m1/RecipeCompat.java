package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Recipe-book facade bridging the 1.21.2 recipe rewrite. 1.21.2+ uses RecipeDisplayEntry +
 * RecipeDisplayId; pre-1.21.2 uses RecipeHolder&lt;?&gt;. The FLOW is identical -- recipe book -&gt;
 * collections -&gt; getRecipes() -&gt; feasibility -&gt; MultiPlayerGameMode.handlePlaceRecipe (which
 * exists in BOTH eras, taking the era's handle type). Entry handles are opaque {@code Object};
 * callers never name the era type. Reflection form for the mojmap runtimes (26+, NeoForge,
 * Forge/FG6 1.20.1+); the Cog twin (_codegen) serves pre-26 Fabric. Keep the surfaces identical.
 */
public final class RecipeCompat {
    private RecipeCompat() {}

    private static Method m(Object o, String name, int argc) {
        for (Method mm : o.getClass().getMethods()) {
            if (mm.getName().equals(name) && mm.getParameterCount() == argc) {
                return mm;
            }
        }
        return null;
    }

    private static Object newInst(String cls) throws Exception {
        Constructor<?> c = Class.forName(cls).getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    /** The Recipe from a handle: RecipeHolder.value() (1.20.2+) or the handle itself (Recipe pre-1.20.2). */
    private static Object recipeOf(Object entry) throws Exception {
        Method vm = m(entry, "value", 0);
        return vm != null ? vm.invoke(entry) : entry;
    }

    /** All recipe-book entry handles (RecipeDisplayEntry or RecipeHolder), every collection flattened. */
    public static List<Object> entries(Minecraft mc) {
        List<Object> out = new ArrayList<>();
        try {
            Object book = m(mc.player, "getRecipeBook", 0).invoke(mc.player);
            Object cols = m(book, "getCollections", 0).invoke(book);
            for (Object c : (Iterable<?>) cols) {
                Object rs = m(c, "getRecipes", 0).invoke(c);
                for (Object e : (Iterable<?>) rs) {
                    out.add(e);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Result item id ("namespace:path") of an entry, or "" when unresolvable. */
    public static String resultId(Minecraft mc, Object entry) {
        try {
            ItemStack first = null;
            Method resultItems = m(entry, "resultItems", 1);
            if (resultItems != null) {                       // 1.21.2+: resultItems(ContextMap)
                Object ctx = slotContext(mc);
                Object rs = resultItems.invoke(entry, ctx);
                List<?> list = (List<?>) rs;
                if (!list.isEmpty()) {
                    first = (ItemStack) list.get(0);
                }
            } else {                                         // pre: RecipeHolder.value().getResultItem(reg)
                Object recipe = recipeOf(entry);
                Method gri = m(recipe, "getResultItem", 1);
                first = (ItemStack) gri.invoke(recipe, mc.level.registryAccess());
            }
            if (first == null || first.isEmpty()) {
                return "";
            }
            return BuiltInRegistries.ITEM.getKey(first.getItem()).toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static Object slotContext(Minecraft mc) throws Exception {
        Class<?> sdc = Class.forName("net.minecraft.world.item.crafting.display.SlotDisplayContext");
        for (Method mm : sdc.getMethods()) {
            if (mm.getName().equals("fromLevel") && mm.getParameterCount() == 1) {
                return mm.invoke(null, mc.level);
            }
        }
        throw new NoSuchMethodException("SlotDisplayContext.fromLevel");
    }

    private static void fillStacked(Minecraft mc, Object contents) throws Exception {
        Object inv = m(mc.player, "getInventory", 0).invoke(mc.player);
        for (Method mm : inv.getClass().getMethods()) {
            if (mm.getName().equals("fillStackedContents") && mm.getParameterCount() == 1) {
                mm.invoke(inv, contents);
                return;
            }
        }
    }

    /** Can the player craft this entry with their current inventory? */
    public static boolean canCraft(Minecraft mc, Object entry) {
        try {
            Method cc = m(entry, "canCraft", 1);
            if (cc != null) {                                // 1.21.2+: entry.canCraft(StackedItemContents)
                Object sic = newInst("net.minecraft.world.entity.player.StackedItemContents");
                fillStacked(mc, sic);
                return (Boolean) cc.invoke(entry, sic);
            }
            Object recipe = recipeOf(entry);   // pre: StackedContents.canCraft(recipe, null)
            Object sc = newInst("net.minecraft.world.entity.player.StackedContents");
            fillStacked(mc, sc);
            for (Method mm : sc.getClass().getMethods()) {
                if (mm.getName().equals("canCraft") && mm.getParameterCount() == 2) {
                    return (Boolean) mm.invoke(sc, recipe, null);
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** The recipe's ingredient list (empty when unresolved/unsynced). */
    public static List<Ingredient> requirements(Object entry) {
        List<Ingredient> out = new ArrayList<>();
        try {
            Method cr = m(entry, "craftingRequirements", 0);
            if (cr != null) {                                // 1.21.2+: Optional<List<Ingredient>>
                Object opt = cr.invoke(entry);
                Object present = ((Optional<?>) opt).orElse(null);
                if (present != null) {
                    for (Object ing : (Iterable<?>) present) {
                        out.add((Ingredient) ing);
                    }
                }
                return out;
            }
            Object recipe = recipeOf(entry);   // pre: recipe.getIngredients()
            Method gi = m(recipe, "getIngredients", 0);
            if (gi != null) {
                for (Object ing : (Iterable<?>) gi.invoke(recipe)) {
                    out.add((Ingredient) ing);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Item ids an ingredient accepts (across items()->Stream/List and pre getItems()->ItemStack[]). */
    public static List<String> ingredientItemIds(Ingredient ing) {
        List<String> out = new ArrayList<>();
        try {
            Method items = m(ing, "items", 0);
            if (items != null) {                             // 1.21.2+: Stream/List of Holder<Item>
                Object r = items.invoke(ing);
                Iterable<?> it = (r instanceof Stream) ? (Iterable<?>) ((Stream<?>) r).toList() : (Iterable<?>) r;
                for (Object h : it) {
                    Object item = m(h, "value", 0).invoke(h);
                    out.add(BuiltInRegistries.ITEM.getKey((Item) item).toString());
                }
            } else {                                         // pre: getItems() -> ItemStack[]/Collection
                Object r = m(ing, "getItems", 0).invoke(ing);
                Iterable<?> it = r.getClass().isArray() ? Arrays.asList((Object[]) r) : (Iterable<?>) r;
                for (Object st : it) {
                    out.add(BuiltInRegistries.ITEM.getKey(((ItemStack) st).getItem()).toString());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Fill the open grid from this recipe (the recipe-book click), across the handle-type change. */
    public static void placeRecipe(Minecraft mc, int containerId, Object entry, boolean shift) {
        try {
            Object gm = mc.gameMode;
            Method place = null;
            for (Method mm : gm.getClass().getMethods()) {
                if (mm.getName().equals("handlePlaceRecipe") && mm.getParameterCount() == 3) {
                    place = mm;
                    break;
                }
            }
            if (place == null) {
                return;
            }
            Class<?> mid = place.getParameterTypes()[1];
            Object arg = mid.isInstance(entry) ? entry : m(entry, "id", 0).invoke(entry);
            place.invoke(gm, containerId, arg, shift);
        } catch (Exception ignored) {
        }
    }

    /** Short debug label for an entry (recipe index on 1.21.2+, id string pre). */
    public static String label(Object entry) {
        try {
            Method idm = m(entry, "id", 0);
            if (idm == null) {
                idm = m(entry, "getId", 0);
            }
            Object id = idm.invoke(entry);
            Method idx = m(id, "index", 0);
            return "recipe " + (idx != null ? idx.invoke(id) : id);
        } catch (Exception e) {
            return "recipe ?";
        }
    }
}
