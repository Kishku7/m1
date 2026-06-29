package com.kishku7.m1;

import java.lang.reflect.Method;

/**
 * Version facade for path-navigation tweaks whose declaring type drifts. setCanOpenDoors lives on
 * GroundPathNavigation; getNavigation()'s static return type exposes it on 26 but not on some pre-26
 * versions. Invoked reflectively on the runtime nav object (always a GroundPathNavigation).
 */
public final class PathNavCompat {
    private PathNavCompat() {}

    private static Method canOpenDoors;
    private static boolean resolved;

    public static void setCanOpenDoors(Object nav, boolean value) {
        if (!resolved) {
            resolved = true;
            for (Method m : nav.getClass().getMethods()) {
                if (m.getName().equals("setCanOpenDoors") && m.getParameterCount() == 1) { canOpenDoors = m; break; }
            }
        }
        if (canOpenDoors == null) return;
        try { canOpenDoors.invoke(nav, value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("setCanOpenDoors: " + e.getMessage(), e); }
    }
}
