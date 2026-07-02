package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;

/**
 * Leaf action that drops the held item -- one item, or the whole stack with {@code all} -- via
 * {@code LocalPlayer.drop(boolean)}, the vanilla drop-key path (sends the proper serverbound
 * action packet, desync-safe). Instantaneous (one step -> DONE/FAILED).
 */
public final class DropAction implements MinecraftAction {

    private final boolean all;

    public DropAction(boolean all) {
        this.all = all;
    }

    @Override
    public String name() {
        return "drop";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, "drop: not in world");
            return StepResult.FAILED;
        }
        String held = Crafting.describeItem(mc.player.getMainHandItem());
        boolean ok = mc.player.drop(all);
        ctx.report(ReportClass.STATUS, ok
                ? "drop: dropped " + (all ? "stack of " : "1 of ") + held
                : "drop: nothing to drop (held " + held + ")");
        return ok ? StepResult.DONE : StepResult.FAILED;
    }
}
