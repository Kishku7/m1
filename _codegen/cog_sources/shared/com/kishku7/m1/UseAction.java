package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;

/**
 * Leaf action that right-clicks the crosshair block target (use/place) via the proven
 * {@link Crafting#place} path -- {@code gameMode.useItemOn}. Covers BOTH placing the held block
 * and activating/using a block (door, lever, button, opening a container). The caller aims first
 * (see {@link LookAction}). Instantaneous (one step -> DONE/FAILED).
 */
public final class UseAction implements MinecraftAction {

    @Override
    public String name() {
        return "use";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, "use: not in world");
            return StepResult.FAILED;
        }
        String r = Crafting.place(mc);
        ctx.report(ReportClass.STATUS, "use: " + r);
        return r.startsWith("OK") ? StepResult.DONE : StepResult.FAILED;
    }
}
