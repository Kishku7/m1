package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

import java.util.List;
import java.util.Locale;

/**
 * Generic craft leaf: {@code agent craft <item> [count]}. Recipe-book driven (see
 * {@link RecipeOps}): picks a producing recipe the player can satisfy, fills the open crafting
 * grid with the authoritative recipe-book fill ({@code gameMode.handlePlaceRecipe}), collects the
 * result with {@link CraftHarvest}, and repeats until {@code count} is reached.
 *
 * Grid: works in the vanilla recipe-book menus -- the 2x2 inventory grid or a crafting table
 * (3x3). A 3x3-only recipe fails in the 2x2 with a hint. (The Bank Vault grid is NOT a
 * RecipeBookMenu; the server ignores place-recipe packets for it -- vault = storage source.)
 *
 * Vault sourcing: if an ingredient is missing but storage memory says it is in the bank vault,
 * the action withdraws it via {@code /bank withdraw} and retries (the vault is player-bound, so
 * the withdraw works while crafting anywhere in the world).
 *
 * NOTE (RULE 3): 26-only recipe APIs used directly; the pre-26 port is deferred to 1.0.
 */
public final class CraftAction implements MinecraftAction {

    private static final int SOURCING_ROUNDS = 3;
    private static final int SOURCING_WAIT = 15;
    private static final int HARVEST_TIMEOUT = 120;

    private final String item;
    private final int want;

    private int state;              // 0 resolve/source, 1 place, 2 start-harvest, 3 wait-harvest
    private int wait;
    private int sourcing;
    private int startCount = -1;
    private int placedRounds;
    private int lastCount = -1;
    private RecipeDisplayEntry entry;
    private String resultId = "";

    public CraftAction(String item, int count) {
        this.item = item.toLowerCase(Locale.ROOT).trim();
        this.want = Math.max(1, count);
    }

    @Override
    public String name() {
        return "craft";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, "craft: not in world");
            return StepResult.FAILED;
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        int resultSlot = resultSlotOf(menu);
        if (resultSlot < 0) {
            ctx.report(ReportClass.STATUS, "craft: no crafting grid open -- openinv (2x2) or open a "
                    + "crafting table (3x3), then retry" + (VaultOps.isVaultMenu(menu)
                    ? " (the vault grid cannot take recipe-book fills -- close it first)" : ""));
            return StepResult.FAILED;
        }
        switch (state) {
            case 0: {
                if (wait > 0) {
                    wait--;
                    return StepResult.RUNNING;
                }
                List<RecipeDisplayEntry> prods = RecipeOps.producersOf(mc, item);
                if (prods.isEmpty()) {
                    ctx.report(ReportClass.STATUS, "craft: no recipe for '" + item + "' in the recipe book");
                    return StepResult.FAILED;
                }
                StackedItemContents sic = RecipeOps.playerContents(mc);
                entry = null;
                for (RecipeDisplayEntry e : prods) {
                    if (e.canCraft(sic)) {
                        entry = e;
                        break;
                    }
                }
                if (entry == null) {
                    if (sourcing < SOURCING_ROUNDS && sourceFromVault(mc, ctx, prods.get(0))) {
                        sourcing++;
                        wait = SOURCING_WAIT;   // give the withdraw time to land
                        return StepResult.RUNNING;
                    }
                    ctx.report(ReportClass.STATUS, "craft: missing ingredients -- "
                            + RecipeOps.canCraft(mc, item).replace('\n', ' '));
                    return StepResult.FAILED;
                }
                resultId = RecipeOps.resultId(mc, entry);
                if (startCount < 0) {
                    startCount = countOf(mc, resultId);
                }
                state = 1;
                return StepResult.RUNNING;
            }
            case 1: {
                mc.gameMode.handlePlaceRecipe(menu.containerId, entry.id(), true);
                placedRounds++;
                state = 2;
                wait = 2;   // grid fill + server result need a tick or two
                return StepResult.RUNNING;
            }
            case 2: {
                if (--wait > 0) {
                    return StepResult.RUNNING;
                }
                CraftHarvest.start(resultSlot);
                state = 3;
                wait = HARVEST_TIMEOUT;
                return StepResult.RUNNING;
            }
            case 3:
            default: {
                if (CraftHarvest.isActive() && --wait > 0) {
                    return StepResult.RUNNING;
                }
                int made = countOf(mc, resultId) - startCount;
                if (made >= want) {
                    ctx.report(ReportClass.STATUS, "craft: DONE -- made " + made + " " + resultId
                            + (placedRounds > 1 ? " (" + placedRounds + " rounds)" : ""));
                    return StepResult.DONE;
                }
                if (made == lastCount) {   // a full round produced nothing new
                    ctx.report(ReportClass.STATUS, "craft: stalled at " + Math.max(made, 0) + "/" + want
                            + " " + resultId + " -- grid too small for the recipe, out of ingredients, "
                            + "or the result did not appear");
                    return made > 0 ? StepResult.DONE : StepResult.FAILED;
                }
                lastCount = made;
                state = 0;   // re-resolve (re-checks feasibility for the next round)
                return StepResult.RUNNING;
            }
        }
    }

    /** Try to withdraw ONE missing ingredient from the bank vault (storage-memory guided). */
    private boolean sourceFromVault(Minecraft mc, ActionContext ctx, RecipeDisplayEntry best) {
        if (best.craftingRequirements().isEmpty() || mc.getConnection() == null) {
            return false;
        }
        for (Ingredient ing : best.craftingRequirements().get()) {
            if (RecipeOps.holdsIngredient(mc, ing)) {
                continue;
            }
            String vaultId = RecipeOps.vaultSourceFor(mc, ing);
            if (vaultId != null) {
                int n = Math.max(want, 8);
                mc.getConnection().sendCommand("bank withdraw " + n + " " + vaultId);
                ctx.report(ReportClass.STATUS, "craft: sourcing " + n + " " + vaultId
                        + " from the vault (/bank withdraw)");
                return true;
            }
        }
        return false;
    }

    private static int resultSlotOf(AbstractContainerMenu menu) {
        // Only the vanilla recipe-book crafting menus: handlePlaceRecipe is server-handled ONLY
        // for RecipeBookMenu subclasses. The Bank Vault menu has a 3x3 grid but is NOT one --
        // its grid ignores the place-recipe packet (verified live 2026-07-02). The vault is a
        // storage source here; sourcing works with ANY screen (the bank is player-bound).
        if (menu instanceof CraftingMenu || menu instanceof InventoryMenu) {
            return 0;
        }
        return -1;
    }

    private static int countOf(Minecraft mc, String id) {
        if (id.isEmpty()) {
            return 0;
        }
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && id.equals(BuiltInRegistries.ITEM.getKey(st.getItem()).toString())) {
                n += st.getCount();
            }
        }
        return n;
    }
}
