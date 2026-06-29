package com.kishku7.m1;

import java.lang.reflect.Method;

/**
 * Version facade for path-navigation tweaks whose declaring type / presence drifts.
 *   setCanOpenDoors    -- lives on GroundPathNavigation; getNavigation()'s static type exposes it on
 *                         26 but not on some pre-26 versions. Invoked reflectively on the runtime nav.
 *   setRequiredPathLength -- absent on 1.20 (added later); optional, no-op where missing (default length).
 */
public final class PathNavCompat {
    private PathNavCompat() {}

    private static Method canOpenDoors;
    private static boolean openResolved;
    private static Method requiredPathLength;
    private static boolean rplResolved;

    private static Method find(Object nav, String name) {
        for (Method m : nav.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) return m;
        }
        return null;
    }

    public static void setCanOpenDoors(Object nav, boolean value) {
        if (!openResolved) { openResolved = true; canOpenDoors = find(nav, "setCanOpenDoors"); }
        if (canOpenDoors == null) return;
        try { canOpenDoors.invoke(nav, value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("setCanOpenDoors: " + e.getMessage(), e); }
    }

    public static void setRequiredPathLength(Object nav, float value) {
        if (!rplResolved) { rplResolved = true; requiredPathLength = find(nav, "setRequiredPathLength"); }
        if (requiredPathLength == null) return;   // not present on this version (e.g. 1.20)
        try { requiredPathLength.invoke(nav, value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("setRequiredPathLength: " + e.getMessage(), e); }
    }
}
