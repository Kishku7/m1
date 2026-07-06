package com.kishku7.m1;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.function.Consumer;

/**
 * Cross-version Screenshot.grab facade (Cog). 26: grab(File, String, RenderTarget, int, Consumer);
 * pre-26: grab(File, String, RenderTarget, Consumer). Both have grab(File, RenderTarget, Consumer)
 * for the auto-named case (name == null). Screenshot referenced fully-qualified (no import drift).
 */
public final class ScreenshotCompat {
    private ScreenshotCompat() {}

    /** Grab a screenshot; name == null for an auto-named file. */
    public static void grab(File dir, String name, RenderTarget target, Consumer<Component> cb) {
        //[[[cog
        //import sys; sys.path.insert(0, codegen); import compat
        //for ln in compat.screenshot_grab(mcver): cog.outl(ln)
        //]]]
        if (name == null) { net.minecraft.client.Screenshot.grab(dir, target, cb); return; }
        net.minecraft.client.Screenshot.grab(dir, name, target, cb);
        //[[[end]]]
    }
}
