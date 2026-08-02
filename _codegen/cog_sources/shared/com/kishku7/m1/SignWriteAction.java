package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Write text onto an existing sign.
 *
 * <p>WHY THIS IS AN ACTION AND NOT A PLAIN VERB (learned the hard way, 2026-08-01). The first
 * attempt did the whole thing inside one socket command: right-click the sign, then immediately
 * send {@code ServerboundSignUpdatePacket}. It always failed silently -- the sign read back
 * unchanged. Both packets left in the SAME tick, so the update arrived before the server had
 * processed the open and marked us as the sign's designated editor
 * ({@code playerWhoMayEdit}); an update from a non-editor is dropped without any error.
 *
 * <p>The fix needs a WAIT between the open and the write, and a socket command cannot wait: its
 * handler runs on the render thread, so sleeping there would block the very tick loop that
 * processes the server's response -- a self-deadlock. Tick-stepped actions are the mechanism M1
 * already has for exactly this, so the write became one.
 *
 * <p>Sequence: suppress the sign-edit reflex (time-boxed) -> right-click the sign -> wait for the
 * edit screen to actually appear (proof the server authorised us) -> send the text -> close.
 */
public final class SignWriteAction implements MinecraftAction {

    /** Long enough for a server round-trip on a laggy tick, short enough to fail fast. */
    private static final int OPEN_WAIT_TICKS = 60;
    /** Let the authorisation settle before writing; the screen appearing is the real signal. */
    private static final int SETTLE_TICKS = 2;
    private static final long GRACE_MS = 15_000L;

    private enum Phase { OPEN, AWAIT_SCREEN, SETTLE, SEND, DONE }

    private final BlockPos pos;
    private final List<String> lines;
    private Phase phase = Phase.OPEN;
    private int waited;

    public SignWriteAction(BlockPos pos, List<String> lines) {
        this.pos = pos;
        this.lines = new ArrayList<>(lines);
    }

    @Override
    public String name() {
        return "sign write";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.level == null) {
            ctx.report(ReportClass.STATUS, "sign: not in world");
            return StepResult.FAILED;
        }

        switch (phase) {
            case OPEN: {
                if (ScreenOps.signTextAtPublic(mc, pos) == null) {
                    ctx.report(ReportClass.STATUS, "sign: no sign at (" + pos.getX() + ","
                            + pos.getY() + "," + pos.getZ() + ") -- place one first");
                    return StepResult.FAILED;
                }
                ScreenWatch.allowSign(GRACE_MS);
                String r = ScreenOps.useAt(mc, pos, "sign");
                if (!r.startsWith("OK")) {
                    ctx.report(ReportClass.STATUS, r);
                    return StepResult.FAILED;
                }
                waited = 0;
                phase = Phase.AWAIT_SCREEN;
                return StepResult.RUNNING;
            }
            case AWAIT_SCREEN: {
                // The edit screen appearing is PROOF the server accepted us as the sign's editor.
                // Writing before this is what silently failed.
                if (M1Compat.screen(mc) instanceof AbstractSignEditScreen) {
                    waited = 0;
                    phase = Phase.SETTLE;
                    return StepResult.RUNNING;
                }
                if (++waited > OPEN_WAIT_TICKS) {
                    ctx.report(ReportClass.STATUS, "sign: the edit screen never opened for ("
                            + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                            + ") -- the sign may be WAXED (honeycombed), which cannot be edited");
                    return StepResult.FAILED;
                }
                return StepResult.RUNNING;
            }
            case SETTLE: {
                if (++waited < SETTLE_TICKS) {
                    return StepResult.RUNNING;
                }
                phase = Phase.SEND;
                return StepResult.RUNNING;
            }
            case SEND: {
                SignCompat.sendSignUpdate(mc, pos, true, lines);
                M1Compat.setScreen(mc, null);
                ctx.report(ReportClass.STATUS, "sign (" + pos.getX() + "," + pos.getY() + ","
                        + pos.getZ() + ") = \"" + String.join(" / ", lines).trim() + "\"");
                phase = Phase.DONE;
                return StepResult.DONE;
            }
            default:
                return StepResult.DONE;
        }
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && M1Compat.screen(mc) instanceof AbstractSignEditScreen) {
            M1Compat.setScreen(mc, null);   // never leave the edit dialog hanging
        }
        phase = Phase.OPEN;                 // redo the whole handshake on resume
        waited = 0;
    }
}
