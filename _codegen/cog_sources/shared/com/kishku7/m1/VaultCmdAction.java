package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;

/**
 * Leaf action wrapping a synchronous {@link VaultOps} subcommand (mark/status/contents/
 * withdraw/deposit/find/memory) as a queue action so storage steps can sit in a plan.
 * {@code vault close} is NOT handled here -- that is {@link VaultGuardAction} (tick-stepped,
 * trinket re-equip guard).
 */
public final class VaultCmdAction implements MinecraftAction {

    private final String args;

    public VaultCmdAction(String args) {
        this.args = args;
    }

    @Override
    public String name() {
        return "vault";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, "vault: not in world");
            return StepResult.FAILED;
        }
        String r = VaultOps.command(mc, args);
        ctx.report(ReportClass.STATUS, "vault: " + r);
        return r.startsWith("ERR") ? StepResult.FAILED : StepResult.DONE;
    }
}
