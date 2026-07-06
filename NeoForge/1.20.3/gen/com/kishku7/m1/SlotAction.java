package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;

/**
 * Leaf action wrapping the proven {@link Crafting#slotCmd} container slot click
 * ({@code <id> [button] [pickup|quick|swap]}) as a queue action, so composites can move
 * items between an open container and the player inventory as plan steps. Requires an open
 * container. Instantaneous.
 */
public final class SlotAction implements MinecraftAction {

    private final String args;

    public SlotAction(String args) {
        this.args = args;
    }

    @Override
    public String name() {
        return "slot";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, "slot: not in world");
            return StepResult.FAILED;
        }
        String r = Crafting.slotCmd(mc, args);
        ctx.report(ReportClass.STATUS, "slot: " + r);
        return r.startsWith("OK") ? StepResult.DONE : StepResult.FAILED;
    }
}
