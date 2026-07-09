package com.kishku7.m1;

import net.minecraft.client.gui.screens.Screen;
//[[[cog
//import sys; sys.path.insert(0, codegen); import compat
//if compat.mouse_event(compat.V(mcver)):
//    cog.outl("import net.minecraft.client.input.MouseButtonEvent;")
//    cog.outl("import net.minecraft.client.input.MouseButtonInfo;")
//]]]
//[[[end]]]

/**
 * Cross-version screen-click facade (Cog). 26+: a MouseButtonEvent record + mouseClicked(ev, boolean)
 * + mouseReleased(ev). pre-26: mouseClicked(double, double, int) + mouseReleased(double, double, int).
 * DIRECT per version (resolves on intermediary + mojmap). See _codegen/compat.py.
 */
public final class ScreenClickCompat {
    private ScreenClickCompat() {}

    /** Left-click + release at (x, y) on the screen. Returns whether the click was handled. */
    public static boolean clickAt(Screen s, double x, double y) {
        //[[[cog
        //for ln in compat.screen_click(mcver): cog.outl(ln)
        //]]]
        boolean handled = s.mouseClicked(x, y, 0);
        s.mouseReleased(x, y, 0);
        return handled;
        //[[[end]]]
    }
}
