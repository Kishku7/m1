package com.kishku7.m1;

import net.minecraft.world.entity.player.Inventory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Version facade for the selected hotbar slot.
 *   26 / 1.21.5+ : Inventory.getSelectedSlot() / setSelectedSlot(int)
 *   pre-1.21.5   : the public int field Inventory.selected
 */
public final class InventoryCompat {
    private InventoryCompat() {}

    private static boolean resolved;
    private static Method getter, setter;
    private static Field field;

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            getter = Inventory.class.getMethod("getSelectedSlot");
            setter = Inventory.class.getMethod("setSelectedSlot", int.class);
        } catch (NoSuchMethodException e) {
            try { field = Inventory.class.getField("selected"); }
            catch (NoSuchFieldException e2) {
                throw new IllegalStateException("inventory-selected facade: no accessor or field", e2);
            }
        }
    }

    public static int getSelected(Inventory inv) {
        resolve();
        try { return getter != null ? (int) getter.invoke(inv) : field.getInt(inv); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("getSelected: " + e.getMessage(), e); }
    }

    public static void setSelected(Inventory inv, int slot) {
        resolve();
        try { if (setter != null) setter.invoke(inv, slot); else field.setInt(inv, slot); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("setSelected: " + e.getMessage(), e); }
    }
}
