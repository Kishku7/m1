package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Leaf action that aims the player: either at a world point (x, y, z) or at raw yaw[/pitch].
 * Instantaneous -- one step sets the rotation and returns DONE. Mirrors the math in
 * {@code ScreenOps.face} so the agent queue can sequence "aim, then act" the same way the
 * synchronous {@code face} command does.
 *
 * <p>Foundational for almost every other actuator: {@code use}/{@code place} act on the crosshair
 * target, and {@code mine} (crosshair form) needs facing first.
 */
public final class LookAction implements MinecraftAction {

    private final boolean point;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final Float pitch;

    private LookAction(boolean point, double x, double y, double z, float yaw, Float pitch) {
        this.point = point;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** Face a world point. */
    public static LookAction atPoint(double x, double y, double z) {
        return new LookAction(true, x, y, z, 0f, null);
    }

    /** Set yaw and (optionally) pitch directly. */
    public static LookAction atAngles(float yaw, Float pitch) {
        return new LookAction(false, 0, 0, 0, yaw, pitch);
    }

    @Override
    public String name() {
        return "look";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, "look: not in world");
            return StepResult.FAILED;
        }
        LocalPlayer p = mc.player;
        if (point) {
            Vec3 eye = p.getEyePosition();
            double dx = x - eye.x;
            double dy = y - eye.y;
            double dz = z - eye.z;
            double h = Math.sqrt(dx * dx + dz * dz);
            float yw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pt = (float) (-Math.toDegrees(Math.atan2(dy, h)));
            p.setYRot(yw);
            p.setXRot(pt);
            ctx.report(ReportClass.STATUS, String.format(Locale.ROOT,
                    "look: facing (%.1f,%.1f,%.1f) yaw=%.1f pitch=%.1f", x, y, z, yw, pt));
        } else {
            p.setYRot(yaw);
            if (pitch != null) {
                p.setXRot(pitch);
            }
            ctx.report(ReportClass.STATUS,
                    "look: yaw=" + yaw + (pitch != null ? " pitch=" + pitch : ""));
        }
        return StepResult.DONE;
    }
}
