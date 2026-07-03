package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Travelers Backpack sleeping-bag support for {@link SleepAction} (Master, 2026-07-02).
 * Detection logic ported from Ultimate Sleep's TravelersBackpackCompat (id-based, no compile dep):
 * a usable sleeping bag is either a loose TB "*_sleeping_bag" item in hand/inventory, OR a TB
 * backpack (worn or carried) with a bag ATTACHED (its stack carries a "sleeping_bag_color"
 * data component >= 0).
 *
 * Deploy is client-side: select the loose bag item (if any) and use it aimed at the ground -- the
 * TB sleeping-bag block extends BedBlock, so once placed it is picked up by SleepAction's normal
 * BlockTags.BEDS bed flow. If only an ATTACHED bag exists (no loose item), TB's own keybind/GUI is
 * needed to detach it; M1 reports that rather than failing silently.
 */
final class BagSleep {

    private static final String TB = "travelersbackpack";

    private BagSleep() {}

    private static boolean tbPresent() {
        try {
            Class.forName("com.tiviacz.travelersbackpack.attachment.AttachmentUtils");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True if the player can use a sleeping bag right now. */
    static boolean available(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || !tbPresent()) {
            return false;
        }
        if (looseBagSlot(p) >= 0) {
            return true;
        }
        // attached to a carried backpack, or the worn one (BackpackOps handles the worn stack)
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (hasAttachedBag(p.getInventory().getItem(i))) {
                return true;
            }
        }
        if (BackpackOps.present()) {
            // worn backpack: reuse BackpackOps' reflected getWearingBackpack via a contents probe
            // (cheap: if wearing a backpack at all, an attached bag is plausible -- report path below)
            return wornHasBag(mc);
        }
        return false;
    }

    /** Deploy a loose sleeping-bag item at the player's feet (client use-item aimed down). */
    static void deploy(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return;
        }
        int slot = looseBagSlot(p);
        if (slot < 0) {
            M1Server.log("BagSleep.deploy: no loose sleeping-bag item to place (attached-only bag needs TB keybind)");
            return;
        }
        if (slot <= 8) {
            Crafting.hold(mc, Integer.toString(slot));
        } else {
            Crafting.openInv(mc);
            Crafting.equip(mc, BuiltInRegistries.ITEM.getKey(p.getInventory().getItem(slot).getItem()).getPath());
            Crafting.close(mc);
        }
        // Aim at the TOP FACE of the floor block one cell ahead so useItemOn has a real block hit,
        // and TB places the bag's foot there + head one further ahead. Face a cardinal first so the
        // 2-cell bag has room, then pitch down onto the near floor block.
        // Aim at the TOP of the block directly below our feet -- guaranteed a solid, in-reach hit.
        // TB places the bag foot on that top face and the head one cell in our facing direction, so
        // keep the current yaw (the caller ensured the facing cell is clear via findApproach).
        net.minecraft.core.BlockPos below = p.blockPosition().below();
        double tx = below.getX() + 0.5;
        double tz = below.getZ() + 0.5;
        double ty = below.getY() + 1.0; // top face
        double dx = tx - p.getX();
        double dz = tz - p.getZ();
        double dy = ty - (p.getY() + p.getEyeHeight());
        p.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.max(0.05, Math.hypot(dx, dz)))));
        Crafting.place(mc); // useItemOn the floor below -> deploys the sleeping-bag BedBlock
        // Fallback: some placements want a plain use-item (air) too.
        try {
            net.minecraft.world.phys.HitResult hr = mc.hitResult;
            if (hr == null || hr.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                mc.gameMode.useItem(p, net.minecraft.world.InteractionHand.MAIN_HAND);
            }
        } catch (Throwable ignored) { }
    }

    private static int looseBagSlot(LocalPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (isBagItem(p.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isBagItem(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return id != null && TB.equals(id.getNamespace()) && id.getPath().endsWith("sleeping_bag");
    }

    /** A TB backpack stack with a bag attached (sleeping_bag_color component, >= 0). */
    private static boolean hasAttachedBag(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (TypedDataComponent<?> comp : s.getComponents()) {
            Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(comp.type());
            if (id != null && TB.equals(id.getNamespace()) && id.getPath().contains("sleeping_bag")) {
                Object v = comp.value();
                return !(v instanceof Integer i) || i >= 0;
            }
        }
        return false;
    }

    /** Whether the worn backpack has an attached bag (probe the SERVER player's worn stack). */
    private static boolean wornHasBag(Minecraft mc) {
        try {
            net.minecraft.world.entity.player.Player who = BackpackOps.serverPlayer(mc);
            if (who == null) {
                who = mc.player;
            }
            Class<?> a = Class.forName("com.tiviacz.travelersbackpack.attachment.AttachmentUtils");
            Object worn = a.getMethod("getWearingBackpack", net.minecraft.world.entity.player.Player.class)
                    .invoke(null, who);
            return worn instanceof ItemStack s && hasAttachedBag(s);
        } catch (Throwable t) {
            return false;
        }
    }
}
