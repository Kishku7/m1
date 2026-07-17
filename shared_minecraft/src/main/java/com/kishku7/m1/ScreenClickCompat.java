package com.kishku7.m1;

import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Version facade for synthesizing a left-click on a Screen at (x, y) -- one source, every MC version.
 *   26+    : Screen.mouseClicked(MouseButtonEvent, boolean) + mouseReleased(MouseButtonEvent);
 *            MouseButtonEvent(double x, double y, MouseButtonInfo); MouseButtonInfo(int button, int modifiers).
 *   pre-26 : Screen.mouseClicked(double x, double y, int button) + mouseReleased(double, double, int).
 * Resolved reflectively so business code never names net.minecraft.client.input.* (26-only).
 *
 * PRIMARY-BUTTON DRIFT (26.3-snapshot-3 input rework): AbstractWidget.isValidClickButton flipped from
 * "button() == 0" to "button() == 1" -- i.e. the primary (left) click button is encoded 0 on <=26.2 /
 * pre-26 but 1 on 26.3-snapshot-3+. A widget silently ignores a click whose button value it rejects
 * (mouseClicked returns without firing onClick, yet the Screen still reports handled), so a hardcoded 0
 * leaves menus un-clickable on the new input layer. Rather than pin a version, we PROBE the widget's own
 * isValidClickButton to learn which value it accepts (M1's shape-over-version rule).
 */
public final class ScreenClickCompat {
    private ScreenClickCompat() {}

    private static boolean resolved;
    private static boolean modern;
    private static Method clicked, released;
    private static Constructor<?> eventCtor, infoCtor;
    private static Method isValidClickBtn;     // AbstractWidget.isValidClickButton(MouseButtonInfo) -- modern only
    private static int primaryButton = -1;     // resolved lazily by probing a real widget (0 or 1)

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        for (Method m : Screen.class.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("mouseClicked")) {
                if (p.length == 2 && p[1] == boolean.class) { clicked = m; modern = true; }
                else if (p.length == 3 && p[0] == double.class) { clicked = m; }
            } else if (m.getName().equals("mouseReleased")) {
                if (p.length == 1) { released = m; }
                else if (p.length == 3 && p[0] == double.class) { released = m; }
            }
        }
        if (modern && clicked != null) {
            Class<?> eventType = clicked.getParameterTypes()[0];
            for (Constructor<?> c : eventType.getDeclaredConstructors()) {
                if (c.getParameterCount() == 3 && c.getParameterTypes()[0] == double.class) { eventCtor = c; break; }
            }
            if (eventCtor != null) {
                Class<?> infoType = eventCtor.getParameterTypes()[2];
                try { infoCtor = infoType.getDeclaredConstructor(int.class, int.class); }
                catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("screen-click facade ctor resolve: " + e.getMessage(), e);
                }
                eventCtor.setAccessible(true);
                infoCtor.setAccessible(true);
                // AbstractWidget.isValidClickButton(MouseButtonInfo) -- used to probe the primary button value.
                try {
                    Class<?> widgetCls = Class.forName("net.minecraft.client.gui.components.AbstractWidget");
                    Method mm = widgetCls.getDeclaredMethod("isValidClickButton", infoType);
                    mm.setAccessible(true);
                    isValidClickBtn = mm;
                } catch (ReflectiveOperationException ignored) { /* fall back to button 0 */ }
            }
        }
        if (clicked != null) clicked.setAccessible(true);
        if (released != null) released.setAccessible(true);
    }

    private static Object firstWidget(Screen s) {
        try {
            Class<?> widgetCls = Class.forName("net.minecraft.client.gui.components.AbstractWidget");
            for (Object child : s.children()) {
                if (widgetCls.isInstance(child)) return child;
            }
        } catch (ReflectiveOperationException ignored) { }
        return null;
    }

    /** Which int value the widgets treat as the primary (left) click. Probed once from a real widget; 0 if undeterminable. */
    private static int primaryButton(Screen s) {
        if (primaryButton >= 0) return primaryButton;
        if (isValidClickBtn != null) {
            Object widget = firstWidget(s);
            if (widget != null) {
                for (int cand : new int[]{1, 0}) {   // prefer the new-era value; old widgets reject 1, accept 0
                    try {
                        Object info = infoCtor.newInstance(cand, 0);
                        if ((boolean) isValidClickBtn.invoke(widget, info)) { primaryButton = cand; return cand; }
                    } catch (ReflectiveOperationException ignored) { }
                }
            }
        }
        return 0;   // no probe-able widget yet -> old default; do NOT cache (a later screen may have one)
    }

    /** Left-click + release at (x, y) on the screen. Returns whether the click was handled. */
    public static boolean clickAt(Screen s, double x, double y) {
        resolve();
        if (clicked == null) return false;
        try {
            if (modern) {
                Object info = infoCtor.newInstance(primaryButton(s), 0);
                Object ev = eventCtor.newInstance(x, y, info);
                boolean handled = (boolean) clicked.invoke(s, ev, false);
                if (released != null) released.invoke(s, ev);
                return handled;
            }
            boolean handled = (boolean) clicked.invoke(s, x, y, 0);
            if (released != null) released.invoke(s, x, y, 0);
            return handled;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("screen-click facade failed: " + e.getMessage(), e);
        }
    }
}
