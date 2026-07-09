package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

/**
 * Leaf action: raise a shield and hold the block for a number of ticks, then lower. Basic but real --
 * the "proper use of a shield" primitive Master called out. A shield blocks frontal melee, projectiles,
 * and reduces explosions ONLY while facing the attacker and after a short raise delay, so the caller
 * raises BEFORE the hit (couple with {@link LookAction} / {@link AttackAction} facing).
 *
 * <p>Picks the off-hand shield if present, else a main-hand shield. Keeps the block up by re-starting
 * the item-use if it drops, and lowers on completion. The block/advance/strike LOOP against a
 * skeleton (raise -> advance on its reload -> jump-crit up close) is a Phase 2 composite that drives
 * this action + {@link AttackAction}; see {@code projects/m1-combat.md}.
 */
public final class ShieldAction implements MinecraftAction {

    private final int holdTicks;
    private int left = -1;
    private InteractionHand hand;

    public ShieldAction(int holdTicks) {
        this.holdTicks = Math.max(1, holdTicks);
    }

    @Override
    public String name() {
        return "shield";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, "shield: not in world");
            return StepResult.FAILED;
        }
        LocalPlayer p = mc.player;

        if (left < 0) {
            if (p.getOffhandItem().is(Items.SHIELD)) {
                hand = InteractionHand.OFF_HAND;
            } else if (p.getMainHandItem().is(Items.SHIELD)) {
                hand = InteractionHand.MAIN_HAND;
            } else {
                ctx.report(ReportClass.STATUS, "shield: no shield in hand (equip one first)");
                return StepResult.FAILED;
            }
            left = holdTicks;
            mc.gameMode.useItem(p, hand);          // begin using -> raises the shield
            ctx.report(ReportClass.STATUS, "shield: up (" + hand + ", " + holdTicks + "t)");
        }

        if (!p.isUsingItem()) {
            p.startUsingItem(hand);                 // re-raise if it dropped
        }

        if (--left <= 0) {
            p.stopUsingItem();
            ctx.report(ReportClass.STATUS, "shield: down (was blocking=" + p.isBlocking() + ")");
            return StepResult.DONE;
        }
        return StepResult.RUNNING;
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc != null && mc.player != null) {
            mc.player.stopUsingItem();             // lower while the higher-priority action runs
        }
    }
}
