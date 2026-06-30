package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.ActionQueue;
import com.kishku7.m1.agent.AgentLoop;
import com.kishku7.m1.agent.Budgeter;
import com.kishku7.m1.agent.InterruptSource;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.Report;
import com.kishku7.m1.agent.ReportChannel;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.ServiceLocator;
import com.kishku7.m1.agent.StepResult;
import com.kishku7.m1.agent.WorkerPool;

import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The Minecraft-coupled host for the MC-agnostic agent engine ({@code com.kishku7.m1.agent}).
 *
 * <p>This is the seam between M1's existing client-tick world and the agent core. It owns the
 * single set of engine instances, drives {@link AgentLoop#tick} once per client tick (registered
 * in {@code M1Client} alongside the other M1 subsystems), bridges agent {@link Report}s onto the
 * existing socket reply stream (mirroring {@link PickupUpgrade#drainReports}), and exposes the
 * {@code agent} socket command.
 *
 * <p>Phase 2 scope: wiring only. The reflex {@link InterruptSource} is an empty stub (sensors are a
 * later phase) and the {@link ServiceLocator} resolves just the {@link Minecraft} instance for now;
 * the real action registry, sensors, perception, and navigation register here as later phases land.
 */
public final class AgentRuntime {

    private static final ActionQueue QUEUE = new ActionQueue();
    private static final ReportChannel REPORTS = new ReportChannel();
    private static final Budgeter BUDGETER = new Budgeter(2.0); // ~2 ms/tick discretionary budget
    private static final WorkerPool WORKERS = new WorkerPool(2);
    private static final ServiceLocator SERVICES = new McServices();
    private static final InterruptSource INTERRUPTS = ctx -> Collections.emptyList(); // sensors: later
    private static final AgentLoop LOOP = new AgentLoop(QUEUE, REPORTS, BUDGETER, INTERRUPTS);

    private AgentRuntime() {
    }

    public static ActionQueue queue() {
        return QUEUE;
    }

    public static ReportChannel reports() {
        return REPORTS;
    }

    public static AgentLoop loop() {
        return LOOP;
    }

    public static WorkerPool workers() {
        return WORKERS;
    }

    /** Registered on {@code ClientTickEvents.END_CLIENT_TICK}. Drives the agent one tick in-world. */
    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return; // only run while actually in a world
        }
        LOOP.tick(SERVICES);
    }

    /**
     * Drain pending agent reports as text for the socket, mirroring {@link PickupUpgrade#drainReports}.
     * Each line: {@code [agent] <seq> <CLASS>: <text>}.
     */
    public static String drainReports() {
        List<Report> rs = REPORTS.drain();
        if (rs.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (Report r : rs) {
            b.append("[agent] ").append(r.seq()).append(' ').append(r.cls())
                    .append(": ").append(r.text()).append('\n');
        }
        if (b.length() > 0 && b.charAt(b.length() - 1) == '\n') {
            b.setLength(b.length() - 1);
        }
        return b.toString();
    }

    /** Handle the {@code agent} socket command. {@code rest} is the text after the verb. */
    public static String command(String rest) {
        String arg = (rest == null) ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (arg.isEmpty() || arg.equals("status")) {
            return status();
        }
        if (arg.equals("ping")) {
            QUEUE.append(new PingAction());
            return "queued ping (runs next in-world tick; reports drain onto a later reply)";
        }
        return "agent: unknown subcommand '" + arg + "' (try: status | ping)";
    }

    /** One-line engine status. */
    public static String status() {
        return "agent: tick=" + LOOP.currentTick()
                + " idle=" + QUEUE.isIdle()
                + " backlog=" + QUEUE.backlogSize()
                + " interrupt=" + QUEUE.hasInterrupt()
                + " lastSeq=" + REPORTS.lastSeq()
                + " budgetMs=" + String.format(Locale.ROOT, "%.3f", BUDGETER.avgMillis());
    }

    /**
     * MC-coupled service locator. For now it resolves only the {@link Minecraft} client instance;
     * perception / actuation / world-map services register here as later phases add them.
     */
    private static final class McServices implements ServiceLocator {
        @Override
        public <T> T lookup(Class<T> type) {
            if (type == Minecraft.class) {
                return type.cast(Minecraft.getInstance());
            }
            return null;
        }
    }

    /** Trivial end-to-end probe: emits a status report and finishes. Proves socket -> queue -> loop
     *  -> report -> socket works in-game before real actions exist. */
    private static final class PingAction implements MinecraftAction {
        @Override
        public String name() {
            return "ping";
        }

        @Override
        public StepResult step(ActionContext ctx) {
            ctx.report(ReportClass.STATUS, "pong (tick " + ctx.tick() + ")");
            return StepResult.DONE;
        }
    }
}
