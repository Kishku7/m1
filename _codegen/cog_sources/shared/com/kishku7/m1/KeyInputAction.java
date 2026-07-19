package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Leaf action for the basic movement-key functions, driven through the {@code KeyMapping}
 * objects -- never raw key codes (design principle: users can remap a key but never the
 * function it calls).
 *
 * <ul>
 *   <li>jump = one-shot: press keyJump this tick, release next tick, DONE.</li>
 *   <li>sneak / sprint = persistent state toggles on keyShift / keySprint. NOTE:
 *       {@link MoveControl} overrides keySprint each tick while a path is active (its own
 *       sprint flag wins there), and {@code agent stop} releases both toggles.</li>
 * </ul>
 */
public final class KeyInputAction implements MinecraftAction {

    /** Which key function this action drives. */
    public enum Mode { JUMP, SNEAK_ON, SNEAK_OFF, SPRINT_ON, SPRINT_OFF }

    private final Mode mode;
    private boolean pressed;

    public KeyInputAction(Mode mode) {
        this.mode = mode;
    }

    @Override
    public String name() {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.options == null) {
            ctx.report(ReportClass.STATUS, name() + ": not in world");
            return StepResult.FAILED;
        }
        switch (mode) {
            case JUMP:
                if (!pressed) {
                    mc.options.keyJump.setDown(true);
                    pressed = true;
                    return StepResult.RUNNING;
                }
                mc.options.keyJump.setDown(false);
                ctx.report(ReportClass.STATUS, "jump: done");
                return StepResult.DONE;
            case SNEAK_ON:
            case SNEAK_OFF: {
                boolean on = (mode == Mode.SNEAK_ON);
                mc.options.keyShift.setDown(on);
                ctx.report(ReportClass.STATUS, "sneak: " + (on ? "on" : "off"));
                return StepResult.DONE;
            }
            case SPRINT_ON:
            case SPRINT_OFF:
            default: {
                boolean on = (mode == Mode.SPRINT_ON);
                mc.options.keySprint.setDown(on);
                ctx.report(ReportClass.STATUS, "sprint: " + (on ? "on" : "off"));
                return StepResult.DONE;
            }
        }
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        // never leave a one-shot jump key stuck down across an interrupt
        if (mode == Mode.JUMP && pressed) {
            Minecraft mc = ctx.service(Minecraft.class);
            if (mc != null && mc.options != null) {
                mc.options.keyJump.setDown(false);
            }
        }
    }
}
