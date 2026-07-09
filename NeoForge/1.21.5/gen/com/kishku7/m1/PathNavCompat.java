package com.kishku7.m1;

import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

/**
 * Cross-version path-navigation facade.
 *  - setCanOpenDoors lives on GroundPathNavigation (stable on all versions); the proxy nav IS one, so an
 *    instanceof-cast reaches it regardless of getNavigation()'s static return type. No drift -> no Cog.
 *  - setRequiredPathLength was added at 1.21.2; absent before -> Cog emits the call or a no-op comment.
 * _codegen/compat.py.
 */
public final class PathNavCompat {
    private PathNavCompat() {}

    public static void setCanOpenDoors(PathNavigation nav, boolean value) {
        if (nav instanceof GroundPathNavigation g) {
            g.setCanOpenDoors(value);
        }
    }

    public static void setRequiredPathLength(PathNavigation nav, float value) {
        //[[[cog
        //import sys; sys.path.insert(0, codegen); import compat
        //for ln in compat.required_path_length(mcver): cog.outl(ln)
        //]]]
        nav.setRequiredPathLength(value);
        //[[[end]]]
    }
}
