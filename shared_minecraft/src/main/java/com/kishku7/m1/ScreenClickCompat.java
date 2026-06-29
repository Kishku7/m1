package com.kishku7.m1;

import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Version facade for synthesizing a left-click on a Screen at (x, y) -- one source, every MC version.
 *   26+    : Screen.mouseClicked(MouseButtonEvent, boolean) + mouseReleased(MouseButtonEvent);
 *            MouseButtonEvent(double x, double y, MouseButtonInfo); MouseButtonInfo(int, int).
 *   pre-26 : Screen.mouseClicked(double x, double y, int button) + mouseReleased(double, double, int).
 * Resolved reflectively so business code never names net.minecraft.client.input.* (26-only).
 */
public final class ScreenClickCompat {
    private ScreenClickCompat() {}

    private static boolean resolved;
    private static boolean modern;
    private static Method clicked, released;
    private static Constructor<?> eventCtor, infoCtor;

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
            }
        }
        if (clicked != null) clicked.setAccessible(true);
        if (released != null) released.setAccessible(true);
    }

    /** Left-click + release at (x, y) on the screen. Returns whether the click was handled. */
    public static boolean clickAt(Screen s, double x, double y) {
        resolve();
        if (clicked == null) return false;
        try {
            if (modern) {
                Object info = infoCtor.newInstance(0, 0);
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
