package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Leaf action that breaks the block at a fixed (x, y, z) via M1's existing {@link MineControl}
 * (the same tick-driven break loop the {@code mine} socket command uses). Interruptible exactly
 * like {@link MoveAction}: it starts the break, returns RUNNING while {@link MineControl#isActive()},
 * and on completion re-reads the world -- DONE if the target is now air, FAILED otherwise
 * (timeout / blocked / wrong tool).
 *
 * <p>On a reflex interrupt it stops the break and clears the started flag, so on resume it
 * re-checks the block (already-broken -> DONE) and re-starts if still solid.
 */
public final class MineAction implements MinecraftAction {

    private final int x;
    private final int y;
    private final int z;
    private boolean started;

    public MineAction(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public String name() {
        return "mine";
    }

    @Override
    @SuppressWarnings("deprecation") // Forge 1.20.1-only: BuiltInRegistries access deprecated there (ForgeRegistries); vanilla registry is correct + cross-loader
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.level == null) {
            ctx.report(ReportClass.STATUS, "mine: not in world");
            return StepResult.FAILED;
        }
        BlockPos target = new BlockPos(x, y, z);

        if (!started) {
            started = true;
            if (mc.level.getBlockState(target).isAir()) {
                ctx.report(ReportClass.STATUS, "mine: target already air (" + x + "," + y + "," + z + ")");
                return StepResult.DONE;
            }
            String id = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(target).getBlock()).getPath();
            MineControl.start(target, 200);
            ctx.report(ReportClass.STATUS, "mine: started on " + id + " (" + x + "," + y + "," + z + ")");
            return MineControl.isActive() ? StepResult.RUNNING : StepResult.FAILED;
        }

        if (MineControl.isActive()) {
            return StepResult.RUNNING;
        }
        if (mc.level.getBlockState(target).isAir()) {
            ctx.report(ReportClass.STATUS, "mine: broke (" + x + "," + y + "," + z + ")");
            return StepResult.DONE;
        }
        ctx.report(ReportClass.STATUS, "mine: stopped, block still present (" + x + "," + y + "," + z + ")");
        return StepResult.FAILED;
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        MineControl.stop();   // release the dig while the reflex runs
        started = false;      // re-check / re-start from the new state on resume
    }
}
