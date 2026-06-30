package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;

/**
 * Leaf action that moves an item (matched by id substring) into hotbar slot 0 and selects it, via
 * the proven {@link Crafting#equip} path. Requires an open container (the slot-click protocol).
 * Instantaneous (one step -> DONE/FAILED).
 */
public final class EquipAction implements MinecraftAction {

    private final String item;

    public EquipAction(String item) {
        this.item = item;
    }

    @Override
    public String name() {
        return "equip";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, "equip: not in world");
            return StepResult.FAILED;
        }
        String r = Crafting.equip(mc, item);
        ctx.report(ReportClass.STATUS, "equip: " + r);
        return r.startsWith("OK") ? StepResult.DONE : StepResult.FAILED;
    }
}
