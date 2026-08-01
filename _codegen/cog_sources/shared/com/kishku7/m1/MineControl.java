package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Async mining controller. Holds the attack keybind (mouse-1 / options.keyAttack) while facing a
 * target block, until the block breaks or it times out. No mixin: this is exactly what holding
 * left-click does; the game's continueAttack reads keyAttack each tick (when the mouse is grabbed).
 *
 * <p>Two modes:
 * <ul>
 *   <li>TARGET ({@link #start}) -- face a specific block and hold until it is air or the tick
 *       budget runs out. Because the client breaks whatever the crosshair ray actually hits, a
 *       distant target tunnels through the blocks in between; that is deliberate and useful.</li>
 *   <li>HOLD ({@link #startHold}) -- just hold the attack input, do NOT touch facing. Combined
 *       with a queued move this is "hold down the mine button and walk forward" (Master's own
 *       description, 2026-08-01): continuous tunnelling with ONE command instead of one socket
 *       round-trip per block.</li>
 * </ul>
 */
public final class MineControl {

    /** Safety cap on an untargeted hold so it can never run forever unattended (5 min). */
    public static final int HOLD_MAX_TICKS = 6000;

    private static volatile boolean active = false;
    private static volatile boolean holding = false;
    private static BlockPos target;
    private static int ticks, maxTicks;

    private MineControl() {}

    public static synchronized void start(BlockPos t, int maxT) {
        target = t; ticks = 0; maxTicks = maxT; holding = false; active = true;
    }

    /** Untargeted continuous mining: hold mouse-1, leave facing to the caller. */
    public static synchronized void startHold(int maxT) {
        target = null; ticks = 0;
        maxTicks = (maxT <= 0 || maxT > HOLD_MAX_TICKS) ? HOLD_MAX_TICKS : maxT;
        holding = true; active = true;
    }

    public static synchronized void stop() {
        active = false; holding = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) release(mc); // never leave mouse-1 latched down after a stop
    }

    public static synchronized boolean isActive() { return active; }

    /** True while an untargeted {@code mine hold} is running. */
    public static synchronized boolean isHolding() { return holding; }

    /** Ticks remaining on the current hold/dig budget (0 when idle). */
    public static synchronized int remainingTicks() { return active ? Math.max(0, maxTicks - ticks) : 0; }

    /** Registered on ClientTickEvents.END_CLIENT_TICK. */
    public static synchronized void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { active = false; holding = false; release(mc); return; }
        if (M1Compat.screen(mc) != null) { release(mc); return; }
        ticks++;
        if (ticks > maxTicks) {
            active = false; holding = false; release(mc);
            return;
        }
        if (holding) {
            mc.options.keyAttack.setDown(true); // crosshair-relative: whatever we face, we break
            return;
        }
        if (mc.level.getBlockState(target).isAir()) {
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
