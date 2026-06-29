package com.kishku7.m1;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Version facade for Screenshot.grab, whose named overload drifts:
 *   26     : grab(File, String, RenderTarget, int, Consumer)
 *   pre-26 : grab(File, String, RenderTarget, Consumer)
 *   all    : grab(File, RenderTarget, Consumer)   (auto-named)
 */
public final class ScreenshotCompat {
    private ScreenshotCompat() {}

    private static boolean resolved;
    private static Method unnamed;   // (File, RenderTarget, Consumer)
    private static Method named;     // (File, String, RenderTarget, [int,] Consumer)
    private static boolean namedHasInt;

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        for (Method m : Screenshot.class.getMethods()) {
            if (!m.getName().equals("grab")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0] == File.class && p[1] == RenderTarget.class) unnamed = m;
            else if (p.length == 5 && p[1] == String.class && p[3] == int.class) { named = m; namedHasInt = true; }
            else if (p.length == 4 && p[1] == String.class && named == null) named = m;
        }
    }

    /** Grab a screenshot; name == null for an auto-named file. */
    public static void grab(File dir, String name, RenderTarget target, Consumer<Component> callback) {
        resolve();
        try {
            if (name == null && unnamed != null) { unnamed.invoke(null, dir, target, callback); return; }
            if (named != null) {
                if (namedHasInt) named.invoke(null, dir, name, target, 1, callback);
                else named.invoke(null, dir, name, target, callback);
                return;
            }
            if (unnamed != null) unnamed.invoke(null, dir, target, callback);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("screenshot grab facade: " + e.getMessage(), e);
        }
    }
}
