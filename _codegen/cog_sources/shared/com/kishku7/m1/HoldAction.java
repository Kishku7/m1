package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;

/**
 * Leaf action that selects hotbar slot 0..8 as the held item, via the proven
 * {@link Crafting#hold} path. Instantaneous (one step -> DONE/FAILED).
 */
public final class HoldAction implements MinecraftAction {

    private final int slot;

    public HoldAction(int slot) {
        this.slot = slot;
    }

    @Override
    public String name() {
        return "hold";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, "hold: not in world");
            return StepResult.FAILED;
        }
        String r = Crafting.hold(mc, Integer.toString(slot));
        ctx.report(ReportClass.STATUS, "hold: " + r);
        return r.startsWith("OK") ? StepResult.DONE : StepResult.FAILED;
    }
}
