package com.kishku7.m1;

import net.minecraft.world.entity.player.Inventory;

/**
 * Cross-version selected-hotbar-slot facade (Cog). 1.21.5+/26: getSelectedSlot()/setSelectedSlot(int);
 * pre-1.21.5: the public int field Inventory.selected. DIRECT per version. _codegen/compat.py.
 */
public final class InventoryCompat {
    private InventoryCompat() {}

    public static int getSelected(Inventory inv) {
        //[[[cog
        //import sys; sys.path.insert(0, codegen); import compat
        //for ln in compat.selected_get(mcver): cog.outl(ln)
        //]]]
        return inv.selected;
        //[[[end]]]
    }

    public static void setSelected(Inventory inv, int slot) {
        //[[[cog
        //for ln in compat.selected_set(mcver): cog.outl(ln)
        //]]]
        inv.selected = slot;
        //[[[end]]]
    }
}
