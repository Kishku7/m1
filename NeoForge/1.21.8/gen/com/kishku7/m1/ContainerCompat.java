package com.kishku7.m1;

import net.minecraft.client.Minecraft;
//[[[cog
//import sys; sys.path.insert(0, codegen); import compat
//if compat.container_input(compat.V(mcver)):
//    cog.outl("import net.minecraft.world.inventory.ContainerInput;")
//else:
//    cog.outl("import net.minecraft.world.inventory.ClickType;")
//]]]
import net.minecraft.world.inventory.ClickType;
//[[[end]]]

/**
 * Cross-version container-click facade (Cog). 26: gameMode.handleContainerInput(...,ContainerInput,...);
 * pre-26: gameMode.handleInventoryMouseClick(...,ClickType,...). The loader/version-agnostic Mode enum
 * is mapped to the per-era enum by name. DIRECT per version (intermediary + mojmap). _codegen/compat.py.
 */
public final class ContainerCompat {
    private ContainerCompat() {}

    /** Loader/version-agnostic click intents (mapped to the per-version enum by name). */
    public enum Mode { PICKUP, QUICK_MOVE, SWAP }

    /** Drive one container-menu slot click (the desync-safe protocol). */
    public static void click(Minecraft mc, int containerId, int slot, int button, Mode mode) {
        //[[[cog
        //for ln in compat.container_click(mcver): cog.outl(ln)
        //]]]
        if (mc.gameMode == null) return;
        mc.gameMode.handleInventoryMouseClick(containerId, slot, button, ClickType.valueOf(mode.name()), mc.player);
        //[[[end]]]
    }
}
