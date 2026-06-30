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
 * <p>Phase 1 basics wired here as queue actions: movement (goto/moveto/patrol), aim ({@link
 * LookAction}), break ({@link MineAction}), and the inventory verbs hold/equip/use that wrap the
 * proven {@link Crafting} path. The reflex {@link InterruptSource} is still an empty stub (sensors
 * are a later phase) and the {@link ServiceLocator} resolves just the {@link Minecraft} instance;
 * the real registry, sensors, perception and navigation register here as later phases land.
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
        String s = (rest == null) ? "" : rest.trim();
        if (s.isEmpty()) {
            return status();
        }
        String[] parts = s.split("\\s+", 2);
        String sub = parts[0].toLowerCase(Locale.ROOT);
        String args = (parts.length > 1) ? parts[1].trim() : "";
        switch (sub) {
            case "status":
                return status();
            case "ping":
                QUEUE.append(new PingAction());
                return "queued ping (runs next in-world tick; reports drain onto a later reply)";
            case "goto":
                return enqueueGoto(args);
            case "moveto":
                return enqueueMoveto(args);
            case "patrol":
                return enqueuePatrol(args);
            case "look":
                return enqueueLook(args);
            case "mine":
                return enqueueMine(args);
            case "hold":
                return enqueueHold(args);
            case "equip":
                return enqueueEquip(args);
            case "use":
            case "place":
                QUEUE.append(new UseAction());
                return "queued use/place (acts on the crosshair block next in-world tick)";
            case "stop":
                QUEUE.replace(Collections.<MinecraftAction>emptyList());
                MoveControl.stop();
                MineControl.stop();
                return "agent: plan cleared and movement/mining stopped";
            default:
                return "agent: unknown subcommand '" + sub + "' (try: status | ping | "
                        + "goto <x y z> | moveto <x z> | patrol <x z ...> | look <x y z|yaw [pitch]> | "
                        + "mine <x y z> | hold <0-8> | equip <item> | use | stop)";
        }
    }

    private static String enqueueGoto(String args) {
        String[] t = args.split("\\s+");
        if (t.length < 3) {
            return "usage: agent goto <x> <y> <z>";
        }
        try {
            double x = Double.parseDouble(t[0]);
            double y = Double.parseDouble(t[1]);
            double z = Double.parseDouble(t[2]);
            QUEUE.append(new MoveAction(x, y, z, 1.0, "agent goto"));
            return "queued goto (" + x + ", " + y + ", " + z + ")";
        } catch (NumberFormatException e) {
            return "usage: agent goto <x> <y> <z>";
        }
    }

    private static String enqueueMoveto(String args) {
        String[] t = args.split("\\s+");
        if (t.length < 2) {
            return "usage: agent moveto <x> <z>";
        }
        try {
            double x = Double.parseDouble(t[0]);
            double z = Double.parseDouble(t[1]);
            QUEUE.append(new MoveAction(x, Double.NaN, z, 1.0, "agent moveto"));
            return "queued moveto (" + x + ", " + z + ")";
        } catch (NumberFormatException e) {
            return "usage: agent moveto <x> <z>";
        }
    }

    private static String enqueuePatrol(String args) {
        String[] t = args.split("\\s+");
        if (t.length < 2 || (t.length % 2) != 0) {
            return "usage: agent patrol <x1> <z1> [<x2> <z2> ...]";
        }
        try {
            double[][] pts = new double[t.length / 2][];
            for (int k = 0; k < pts.length; k++) {
                pts[k] = new double[] {Double.parseDouble(t[2 * k]), Double.parseDouble(t[2 * k + 1])};
            }
            QUEUE.append(new PatrolAction(pts));
            return "queued patrol (" + pts.length + " waypoints)";
        } catch (NumberFormatException e) {
            return "usage: agent patrol <x1> <z1> [<x2> <z2> ...]";
        }
    }

    private static String enqueueLook(String args) {
        String[] t = args.split("\\s+");
        if (args.isEmpty() || t[0].isEmpty()) {
            return "usage: agent look <x y z> | <yaw [pitch]>";
        }
        try {
            if (t.length >= 3) {
                double x = Double.parseDouble(t[0]);
                double y = Double.parseDouble(t[1]);
                double z = Double.parseDouble(t[2]);
                QUEUE.append(LookAction.atPoint(x, y, z));
                return "queued look at (" + x + ", " + y + ", " + z + ")";
            }
            float yaw = Float.parseFloat(t[0]);
            Float pitch = (t.length >= 2) ? Float.valueOf(Float.parseFloat(t[1])) : null;
            QUEUE.append(LookAction.atAngles(yaw, pitch));
            return "queued look yaw=" + yaw + (pitch != null ? " pitch=" + pitch : "");
        } catch (NumberFormatException e) {
            return "usage: agent look <x y z> | <yaw [pitch]>";
        }
    }

    private static String enqueueMine(String args) {
        String[] t = args.split("\\s+");
        if (t.length < 3) {
            return "usage: agent mine <x> <y> <z>";
        }
        try {
            int x = Integer.parseInt(t[0]);
            int y = Integer.parseInt(t[1]);
            int z = Integer.parseInt(t[2]);
            QUEUE.append(new MineAction(x, y, z));
            return "queued mine (" + x + ", " + y + ", " + z + ")";
        } catch (NumberFormatException e) {
            return "usage: agent mine <x> <y> <z>";
        }
    }

    private static String enqueueHold(String args) {
        try {
            int n = Integer.parseInt(args.trim());
            QUEUE.append(new HoldAction(n));
            return "queued hold hotbar " + n;
        } catch (NumberFormatException e) {
            return "usage: agent hold <0-8>";
        }
    }

    private static String enqueueEquip(String args) {
        if (args.isEmpty()) {
            return "usage: agent equip <item> (requires an open container)";
        }
        QUEUE.append(new EquipAction(args));
        return "queued equip " + args;
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
