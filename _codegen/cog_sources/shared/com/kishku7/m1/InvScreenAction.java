package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;

/**
 * Leaf action wrapping {@link Crafting#openInv} / {@link Crafting#close} as queue actions, so
 * craft/storage composites can open the 2x2 inventory grid (or close whatever screen is open)
 * as plan steps. Instantaneous.
 */
public final class InvScreenAction implements MinecraftAction {

    private final boolean open;

    public InvScreenAction(boolean open) {
        this.open = open;
    }

    @Override
    public String name() {
        return open ? "openinv" : "close";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, name() + ": not in world");
            return StepResult.FAILED;
        }
        String r = open ? Crafting.openInv(mc) : Crafting.close(mc);
        ctx.report(ReportClass.STATUS, name() + ": " + r);
        return r.startsWith("OK") ? StepResult.DONE : StepResult.FAILED;
    }
}
