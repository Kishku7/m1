package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Borrows vanilla mob pathfinding for the player. We keep ONE proxy Mob (a zombie, sized close to
 * the player) that is NEVER added to the world and NEVER ticked -- we only call its navigation's
 * createPath() to compute a real vanilla A* Path, then drive the real player along it. Client-only,
 * no mixin: this is plain public-API use, and the proxy is not part of the world.
 *
 * GroundPathNavigation.canUpdatePath() requires the mob to be onGround, so we force that flag before
 * each compute (the proxy is never physics-ticked, so the flag would otherwise be false).
 */
public final class PathOracle {

    private static Mob proxy;
    private static Level proxyLevel;

    private PathOracle() {}

    private static Mob proxy(Minecraft mc) {
        Level lvl = mc.level;
        if (lvl == null) return null;
        if (proxy == null || proxyLevel != lvl) {
            try {
                Mob m = (Mob) SpawnCompat.createNatural(M1Compat.zombie(), lvl);
                if (m == null) return null;
                PathNavigation nav = m.getNavigation();
                PathNavCompat.setCanOpenDoors(nav, true);
                nav.setCanFloat(true);
                PathNavCompat.setRequiredPathLength(nav, 48.0f);
                proxy = m;
                proxyLevel = lvl;
            } catch (Throwable t) {
                M1Server.log("PathOracle proxy create failed: " + t);
                return null;
            }
        }
        return proxy;
    }

    /** Compute a vanilla path from the player to (x,y,z). Returns null if no path (or oracle down). */
    public static synchronized Path compute(Minecraft mc, double x, double y, double z, int reach) {
        LocalPlayer p = mc.player;
        Mob m = proxy(mc);
        if (p == null || m == null) return null;
        m.setPos(p.getX(), p.getY(), p.getZ());
        m.setOnGround(true);                 // satisfy GroundPathNavigation.canUpdatePath()
        m.setDeltaMovement(0, 0, 0);
        m.setYRot(p.getYRot());
        try {
            return m.getNavigation().createPath(x, y, z, reach);
        } catch (Throwable t) {
            M1Server.log("PathOracle compute failed: " + t);
            return null;
        }
    }
}
