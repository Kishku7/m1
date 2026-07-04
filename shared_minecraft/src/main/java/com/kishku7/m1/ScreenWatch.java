package com.kishku7.m1;

import java.util.Locale;

import com.kishku7.m1.agent.ReportClass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.BlockPos;

/**
 * Screen reflexes (702d grave-site fixes, Master 2026-07-02):
 *
 *  - SIGN-EDIT dialogs are AUTO-DISMISSED. Right-clicking a grave-site sign opens the editor,
 *    which silently traps every later command ("Hit done, you are in a dialog"). A bot never
 *    needs to edit a sign; the dismissal is reported so the AI knows what it right-clicked.
 *  - DEATH SCREEN: auto-click Respawn ("rez self"), record the death position + dimension (the
 *    seed of the future corpse-run feature) and report both.
 */
final class ScreenWatch {

    private static boolean signHandled;
    private static boolean deathHandled;
    private static BlockPos lastDeathPos;
    private static String lastDeathDim;

    private ScreenWatch() {}

    /** Ticked from AgentRuntime.tick (every client tick while in a world). */
    static void tick(Minecraft mc) {
        Screen s = M1Compat.screen(mc);
        if (s == null) {
            signHandled = false;
            deathHandled = false;
            return;
        }
        if (s instanceof AbstractSignEditScreen) {
            if (!signHandled) {
                signHandled = true;
                report("screen: a SIGN-EDIT dialog opened (that right-click hit a sign) --"
                        + " auto-dismissed. To remove a sign, mine/attack it instead");
            }
            M1Compat.setScreen(mc, null);
            return;
        }
        signHandled = false;
        if (s instanceof DeathScreen) {
            if (!deathHandled && mc.player != null) {
                deathHandled = true;
                lastDeathPos = mc.player.blockPosition();
                lastDeathDim = (mc.level != null) ? M1Compat.keyId(mc.level.dimension()) : "?";
                report("DIED at " + lastDeathPos.toShortString() + " in " + lastDeathDim
                        + " -- auto-respawning (death spot recorded; drops are there unless keepInventory)");
            }
            for (var child : s.children()) {
                if (child instanceof Button b && b.getMessage() != null
                        && b.getMessage().getString().toLowerCase(Locale.ROOT).contains("respawn")) {
                    M1Compat.pressButton(b);
                    return;
                }
            }
            return;
        }
        deathHandled = false;
    }

    /** Last recorded death position (for the future corpse-run feature). Null if none this session. */
    static BlockPos lastDeathPos() {
        return lastDeathPos;
    }

    static String lastDeathDim() {
        return lastDeathDim;
    }

    private static void report(String t) {
        try {
            AgentRuntime.reports().emit(ReportClass.STATUS, t, 0L);
        } catch (Throwable ignored) {
            // never let a report break the reflex
        }
        M1Server.log(t);
    }
}
