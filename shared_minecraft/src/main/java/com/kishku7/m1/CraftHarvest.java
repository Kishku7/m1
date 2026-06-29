package com.kishku7.m1;

import net.minecraft.client.Minecraft;

/**
 * Collects a crafting result over the next ticks. A crafting result is computed server-side and
 * appears a tick or two after ingredients are placed, and QUICK_MOVE only collects it when the
 * cursor (carried item) is empty. So Crafting places the ingredients (and deposits any leftover
 * from the cursor) synchronously, then starts this harvester, which each tick QUICK_MOVEs the
 * result slot (0) whenever it is non-empty - handling single-output recipes and multi-output ones
 * (e.g. several logs -> planks). Runs on ClientTickEvents.END_CLIENT_TICK.
 */
public final class CraftHarvest {

    private static volatile boolean active = false;
    private static int ticks, emptyTicks;

    private CraftHarvest() {}

    public static synchronized void start() { active = true; ticks = 0; emptyTicks = 0; }

    public static synchronized boolean isActive() { return active; }

    public static synchronized void tick(Minecraft mc) {
        if (!active) return;
        if (mc.player == null || mc.player.containerMenu == null || M1Compat.screen(mc) == null) { active = false; return; }
        ticks++;
        boolean empty = mc.player.containerMenu.getSlot(0).getItem().isEmpty();
        if (!empty) {
            ContainerCompat.click(mc, mc.player.containerMenu.containerId, 0, 0, ContainerCompat.Mode.QUICK_MOVE);
            emptyTicks = 0;
        } else {
            emptyTicks++;
        }
        if (emptyTicks >= 4 || ticks > 80) active = false;
    }
}
