package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Async mining controller. Holds the attack keybind (mouse-1 / options.keyAttack) while facing a
 * target block, until the block breaks or it times out. No mixin: this is exactly what holding
 * left-click does; the game's continueAttack reads keyAttack each tick (when the mouse is grabbed).
 */
public final class MineControl {

    private static volatile boolean active = false;
    private static BlockPos target;
    private static int ticks, maxTicks;

    private MineControl() {}

    public static synchronized void start(BlockPos t, int maxT) {
        target = t; ticks = 0; maxTicks = maxT; active = true;
    }

    public static synchronized void stop() { active = false; }

    public static synchronized boolean isActive() { return active; }

    /** Registered on ClientTickEvents.END_CLIENT_TICK. */
    public static synchronized void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { active = false; release(mc); return; }
        if (M1Compat.screen(mc) != null) { release(mc); return; }
        ticks++;
        boolean air = mc.level.getBlockState(target).isAir();
        if (air || ticks > maxTicks) {
            active = false; release(mc);
            return;
        }
        faceBlock(p, target);
        mc.options.keyAttack.setDown(true);
    }

    private static void release(Minecraft mc) {
        if (mc.options != null) mc.options.keyAttack.setDown(false);
    }

    static void faceBlock(LocalPlayer p, BlockPos b) {
        Vec3 eye = p.getEyePosition();
        double dx = b.getX() + 0.5 - eye.x, dy = b.getY() + 0.5 - eye.y, dz = b.getZ() + 0.5 - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot((float) (-Math.toDegrees(Math.atan2(dy, h))));
    }
}

