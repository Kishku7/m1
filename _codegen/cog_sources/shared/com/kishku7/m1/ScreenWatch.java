package com.kishku7.m1;

import java.util.Locale;

import com.kishku7.m1.agent.ReportClass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.BlockPos;

/**
 * Screen reflexes (702d grave-site fixes, Master 2026-07-02; PAUSE reflex added 2026-08-01 after
 * the "260801 bed fumble" -- the operator had to physically click into the window before `sleep`'s
 * FACE loop could land the crosshair on the bed):
 *
 *  - SIGN-EDIT dialogs are AUTO-DISMISSED. Right-clicking a grave-site sign opens the editor,
 *    which silently traps every later command ("Hit done, you are in a dialog"). A bot never
 *    needs to edit a sign; the dismissal is reported so the AI knows what it right-clicked.
 *  - DEATH SCREEN: auto-click Respawn ("rez self"), record the death position + dimension (the
 *    seed of the future corpse-run feature) and report both.
 *  - PAUSE SCREEN is AUTO-DISMISSED. Vanilla's "Pause on Lost Focus" opens the ESC pause menu the
 *    instant the client window loses OS focus -- which an unattended/automated M1 session does
 *    constantly. While that screen is open, {@code Minecraft.runTick} skips {@code
 *    GameRenderer.pick()} entirely, so {@code mc.hitResult} goes stale: any aim primitive
 *    (LookAction, SleepAction's FACE step, {@code ScreenOps.face}) can set the player's yaw/pitch
 *    all it wants and the crosshair-target never updates to match, so every "am I looking at the
 *    bed yet" check keeps reading the LAST real pick from before the pause -- silent, retries
 *    exhaust, "not looking at a block". A human clicking back into the window (or the operator
 *    reaching over) closes the pause screen and resumes picking, which is why it "just worked"
 *    manually. Root-caused via {@code hitResult}/{@code GameRenderer.pick} skip-on-screen
 *    behavior, not assumed. Also disable the underlying option (see {@link
 *    #disablePauseOnLostFocus}) so the screen stops trying to open in the first place; this reflex
 *    is the backstop for any other path that can still summon it (e.g. the operator manually
 *    hitting Escape mid-session).
 */
final class ScreenWatch {

    private static boolean signHandled;
    private static boolean deathHandled;
    private static boolean pauseHandled;
    /** Wall-clock deadline until which a deliberate pause is left alone. */
    private static volatile long pauseGraceUntil;
    /** Wall-clock deadline until which a DELIBERATE sign edit is left alone (see allowSign). */
    private static volatile long signGraceUntil;
    private static boolean pauseOptionChecked;
    private static BlockPos lastDeathPos;
    private static String lastDeathDim;

    private ScreenWatch() {}

    /** Ticked from AgentRuntime.tick (every client tick while in a world). */
    static void tick(Minecraft mc) {
        if (!pauseOptionChecked) {
            pauseOptionChecked = true;
            disablePauseOnLostFocus(mc);
        }
        Screen s = M1Compat.screen(mc);
        if (s == null) {
            signHandled = false;
            deathHandled = false;
            pauseHandled = false;
            return;
        }
        if (s instanceof PauseScreen) {
            // A pause the CONTROLLER asked for is not the pause this reflex exists to kill.
            // Live bug (Master, 2026-08-01): the reflex ate the menu that `pause` had just opened,
            // one tick later, every time -- so the documented graceful-exit path (pause -> click
            // "Save and Quit to Title" / "Disconnect") could never be driven at all, because the
            // window is unfocused during an unattended session BY DEFINITION. Honour a short
            // explicit grace window instead of guessing from focus state.
            if (pauseGraceUntil > System.currentTimeMillis()) {
                return;   // deliberate: leave it up so the caller can describe + click it
            }
            if (!pauseHandled) {
                pauseHandled = true;
                report("screen: PAUSE menu opened (window lost focus) -- auto-dismissed."
                        + " Aim/pick would otherwise stall while this is up");
            }
            M1Compat.setScreen(mc, null);
            return;
        }
        pauseHandled = false;
        if (s instanceof AbstractSignEditScreen) {
            // A sign edit the CONTROLLER asked for is not the accidental right-click this reflex
            // exists to clear. Without this grace window M1 could read signs but never WRITE one:
            // the dialog was killed the tick it opened, the server dropped us as the designated
            // editor, and every placed sign stayed blank (2026-08-01).
            if (signGraceUntil > System.currentTimeMillis()) {
                return;
            }
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

    /**
     * Turn OFF "Pause on Lost Focus" once per session (client option, not per-world) so the pause
     * screen stops being summoned by the OS focus loss an automated/unattended session causes
     * continuously. Checked once per {@code tick} call series via {@code pauseOptionChecked} --
     * cheap enough to not need a dedicated connect hook, and self-heals if the option ever drifts
     * back on (e.g. a settings-menu visit resets it) since a fresh check runs every reconnect.
     */
    private static void disablePauseOnLostFocus(Minecraft mc) {
        try {
            if (mc.options != null && mc.options.pauseOnLostFocus) {
                mc.options.pauseOnLostFocus = false;
                mc.options.save();
                report("screen: disabled \"Pause on Lost Focus\" (M1 runs unattended; the option"
                        + " was stalling aim/pick whenever the window lost OS focus)");
            }
        } catch (Throwable ignored) {
            // never let an options-save failure break the tick loop; the PauseScreen reflex above
            // still catches it even if this cannot persist the setting
        }
    }

    /**
     * Suppress the PauseScreen reflex for {@code ms} milliseconds because the CONTROLLER is opening
     * the pause menu on purpose (graceful quit / options). Time-boxed so a crash or an abandoned
     * plan can never leave the reflex permanently disabled.
     */
    /**
     * Suppress the sign-edit reflex for {@code ms} while the controller deliberately writes a sign.
     * Time-boxed so a failed write can never leave the reflex disabled.
     */
    static void allowSign(long ms) {
        signGraceUntil = System.currentTimeMillis() + ms;
    }

    static void allowPause(long ms) {
        pauseGraceUntil = System.currentTimeMillis() + ms;
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
