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
 * LookAction}), break ({@link MineAction}), inventory verbs hold/equip/use ({@link Crafting}),
 * item handling ({@link DropAction}, {@link SlotAction}, {@link InvScreenAction}), movement keys
 * ({@link KeyInputAction} jump/sneak/sprint), and combat ({@link AttackAction} cooldown-gated
 * jump-crit, {@link ShieldAction} block). The reflex {@link InterruptSource} is {@link
 * ThreatWatch} (auto-defense); the {@link ServiceLocator} resolves just the {@link Minecraft}
 * instance; the real registry, sensors, perception and navigation register here as later phases
 * land.
 */
public final class AgentRuntime {

    /** 30 minutes of ticks -- a real excavation is a long job, but never an unbounded one. */
    private static final int MINE_AREA_BUDGET = 36000;
    private static final String MINE_USAGE =
            "usage: agent mine <x> <y> <z> | agent mine area <x1 y1 z1> <x2 y2 z2> [nocollect]"
            + " | agent mine hold [on|off]";
    private static final String MINE_AREA_USAGE =
            "usage: agent mine area <x1> <y1> <z1> <x2> <y2> <z2> [collect|nocollect]";

    private static final ActionQueue QUEUE = new ActionQueue();
    private static final ReportChannel REPORTS = new ReportChannel();
    private static final Budgeter BUDGETER = new Budgeter(2.0); // ~2 ms/tick discretionary budget
    private static final WorkerPool WORKERS = new WorkerPool(2);
    private static final ServiceLocator SERVICES = new McServices();
    private static final InterruptSource INTERRUPTS = new ThreatWatch(); // reflex auto-defense (2026-07-01)
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
        ScreenWatch.tick(mc); // sign-dialog dismissal + death auto-respawn (702d)
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
                QUEUE.injectInterrupt(new PingAction());
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
                QUEUE.injectInterrupt(new UseAction());
                return "queued use/place (acts on the crosshair block next in-world tick)";
            case "attack":
                return enqueueAttack(args);
            case "follow":
                return enqueueFollow(args);
            case "shield":
            case "block":
                return enqueueShield(args);
            case "defend":
                return ThreatWatch.command(args);
            case "takeoutput": {
                int r = 16;
                if (!args.isEmpty()) {
                    try {
                        r = Integer.parseInt(args.trim().split("\\s+")[0]);
                    } catch (NumberFormatException e) {
                        return "usage: agent takeoutput [radius]";
                    }
                }
                QUEUE.injectInterrupt(new TakeOutputAction(r));
                return "queued take output (every furnace within " + r + "; output slot only,"
                        + " input + fuel untouched). Progress arrives as [agent] lines; 'stop' cancels.";
            }
            case "craft": {
                String[] ct = args.split("\\s+");
                if (args.isEmpty() || ct[0].isEmpty()) {
                    return "usage: agent craft <item> [count]";
                }
                int cn = 1;
                if (ct.length > 1) {
                    try {
                        cn = Integer.parseInt(ct[1]);
                    } catch (NumberFormatException e) {
                        return "usage: agent craft <item> [count]";
                    }
                }
                QUEUE.injectInterrupt(new CraftAction(ct[0], cn));
                return "queued craft " + ct[0] + " x" + cn + " (needs a crafting grid open)";
            }
            case "vault":
                if (args.equalsIgnoreCase("close")) {
                    QUEUE.injectInterrupt(new VaultGuardAction());
                    return "queued vault close (trinket-guarded)";
                }
                QUEUE.injectInterrupt(new VaultCmdAction(args));
                return "queued vault " + (args.isEmpty() ? "status" : args);
            case "drop":
                return enqueueDrop(args);
            case "jump":
                QUEUE.injectInterrupt(new KeyInputAction(KeyInputAction.Mode.JUMP));
                return "queued jump";
            case "sneak":
                return enqueueToggle("sneak", args);
            case "sprint":
                return enqueueToggle("sprint", args);
            case "slot":
                if (args.isEmpty()) {
                    return "usage: agent slot <id> [button] [pickup|quick|swap]";
                }
                QUEUE.injectInterrupt(new SlotAction(args));
                return "queued slot " + args;
            case "sleep":
                QUEUE.injectInterrupt(new SleepAction());
                return "queued sleep (find a usable bed, walk beside it, sleep in it)";
            case "recover":
                QUEUE.injectInterrupt(new RecoverAction());
                return "queued recover (grave-site: break sign, empty chest, break armor stand,"
                        + " collect drops, equip all, organize hotbar)";
            case "openinv":
                QUEUE.injectInterrupt(new InvScreenAction(true));
                return "queued openinv";
            case "close":
                QUEUE.injectInterrupt(new InvScreenAction(false));
                return "queued close";
            case "stop": {
                QUEUE.replace(Collections.<MinecraftAction>emptyList());
                MoveControl.stop();
                MineControl.stop();
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.options != null) {
                    mc.options.keyShift.setDown(false); // release the sneak toggle too
                    mc.options.keySprint.setDown(false);
                }
                return "agent: plan cleared and movement/mining stopped";
            }
            default:
                return "agent: unknown subcommand '" + sub + "' (try: status | ping | "
                        + "goto <x y z> | moveto <x z> | patrol <x z ...> | look <x y z|yaw [pitch]> | "
                        + "mine <x y z> | mine area <x1 y1 z1> <x2 y2 z2> [nocollect] | mine hold [on|off] | "
                        + "hold <0-8> | equip <item> | use | drop [slot|item] [n|all] | jump | "
                        + "sneak [on|off] | sprint [on|off] | sleep | recover | slot <id> [btn] [mode] | openinv | close | "
                        + "attack [nearest|<id>|crosshair] [crit|normal|ranged] | follow <player> [dist] | "
                        + "shield [ticks] | defend [on|off|status|auto|<player>] | vault <sub> | vault close | craft <item> [count] | stop)";
        }
    }

    /**
     * WHY ONE-SHOT ORDERS INJECT INSTEAD OF APPENDING (live bug, Master 2026-08-01).
     *
     * <p>{@code follow} and {@code patrol} are STANDING plans -- they never complete on their own,
     * they run until {@code stop}. Everything used to go onto the BACKLOG, which only advances when
     * the active task finishes, so an order issued while following sat behind it FOREVER: during a
     * live pillager fight a queued {@code attack nearest} reported {@code queued} and then simply
     * never ran ({@code agent status} showed {@code backlog=1} while follow kept walking). Silent,
     * and exactly when it mattered most.
     *
     * <p>The design already called for this -- m1-agent.md's priority model is
     * {@code standing plan < reflex interrupt < AI override}, and "inject = default" -- but the
     * socket layer only ever called {@code append}. One-shot orders now {@link
     * ActionQueue#injectInterrupt} so they preempt the standing plan and it resumes underneath them
     * afterwards (the same mechanism the defend reflex already used). Only the two standing plans
     * still append.
     */
    private static String enqueueGoto(String args) {
        String[] t = args.split("\\s+");
        if (t.length < 3) {
            return "usage: agent goto <x> <y> <z>";
        }
        try {
            double x = Double.parseDouble(t[0]);
            double y = Double.parseDouble(t[1]);
            double z = Double.parseDouble(t[2]);
            QUEUE.injectInterrupt(new MoveAction(x, y, z, 1.0, "agent goto"));
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
            QUEUE.injectInterrupt(new MoveAction(x, Double.NaN, z, 1.0, "agent moveto"));
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
                QUEUE.injectInterrupt(LookAction.atPoint(x, y, z));
                return "queued look at (" + x + ", " + y + ", " + z + ")";
            }
            float yaw = Float.parseFloat(t[0]);
            Float pitch = (t.length >= 2) ? Float.valueOf(Float.parseFloat(t[1])) : null;
            QUEUE.injectInterrupt(LookAction.atAngles(yaw, pitch));
            return "queued look yaw=" + yaw + (pitch != null ? " pitch=" + pitch : "");
        } catch (NumberFormatException e) {
            return "usage: agent look <x y z> | <yaw [pitch]>";
        }
    }

    /**
     * {@code mine <x y z>} | {@code mine area <x1 y1 z1> <x2 y2 z2> [nocollect]} |
     * {@code mine hold [on|off]}.
     *
     * <p>The two bulk forms exist because one-block-per-command made real excavation unusable
     * (Master, 2026-08-01: a 55-column x 12-level slice was 600+ round-trips). {@code area} is the
     * queued job; {@code hold} is the "hold the button down and walk" primitive.
     */
    private static String enqueueMine(String args) {
        String[] t = args.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) {
            return MINE_USAGE;
        }
        String head = t[0].toLowerCase(Locale.ROOT);

        if (head.equals("hold")) {
            String a = (t.length > 1) ? t[1].toLowerCase(Locale.ROOT) : "on";
            if (a.equals("off")) {
                MineControl.stop();
                return "mine hold: OFF (attack input released)";
            }
            if (!a.equals("on")) {
                return "usage: agent mine hold [on|off]";
            }
            int maxT = MineControl.HOLD_MAX_TICKS;
            if (t.length > 2) {
                try {
                    maxT = Integer.parseInt(t[2]);
                } catch (NumberFormatException e) {
                    return "usage: agent mine hold on [maxTicks]";
                }
            }
            MineControl.startHold(maxT);
            return "mine hold: ON for up to " + (maxT / 20) + "s -- you break whatever the"
                    + " crosshair hits. Steer with 'face'/'agent moveto'; 'mine hold off' or"
                    + " 'stop' to release.";
        }

        if (head.equals("area") || head.equals("box")) {
            boolean collect = true;
            java.util.List<Integer> nums = new java.util.ArrayList<>();
            for (int i = 1; i < t.length; i++) {
                String tok = t[i].toLowerCase(Locale.ROOT);
                if (tok.equals("nocollect")) {
                    collect = false;
                    continue;
                }
                if (tok.equals("collect")) {
                    collect = true;
                    continue;
                }
                try {
                    nums.add(Integer.valueOf(Integer.parseInt(t[i])));
                } catch (NumberFormatException e) {
                    return MINE_AREA_USAGE;
                }
            }
            if (nums.size() != 6) {
                return MINE_AREA_USAGE;
            }
            int x1 = nums.get(0).intValue();
            int y1 = nums.get(1).intValue();
            int z1 = nums.get(2).intValue();
            int x2 = nums.get(3).intValue();
            int y2 = nums.get(4).intValue();
            int z2 = nums.get(5).intValue();
            long vol = MineAreaAction.volumeOf(x1, y1, z1, x2, y2, z2);
            if (vol > MineAreaAction.MAX_VOLUME) {
                return "mine area: that box is " + vol + " cells (cap "
                        + MineAreaAction.MAX_VOLUME + ") -- split it into smaller boxes";
            }
            QUEUE.injectInterrupt(new MineAreaAction(x1, y1, z1, x2, y2, z2, collect, MINE_AREA_BUDGET));
            return "queued mine area (" + x1 + "," + y1 + "," + z1 + ")-(" + x2 + "," + y2 + ","
                    + z2 + ") = " + vol + " cells, collect=" + (collect ? "on" : "off")
                    + ". Progress arrives as [agent] lines; 'stop' cancels.";
        }

        if (t.length < 3) {
            return MINE_USAGE;
        }
        try {
            int x = Integer.parseInt(t[0]);
            int y = Integer.parseInt(t[1]);
            int z = Integer.parseInt(t[2]);
            QUEUE.injectInterrupt(new MineAction(x, y, z));
            return "queued mine (" + x + ", " + y + ", " + z + ")";
        } catch (NumberFormatException e) {
            return MINE_USAGE;
        }
    }

    private static String enqueueHold(String args) {
        try {
            int n = Integer.parseInt(args.trim());
            QUEUE.injectInterrupt(new HoldAction(n));
            return "queued hold hotbar " + n;
        } catch (NumberFormatException e) {
            return "usage: agent hold <0-8>";
        }
    }

    private static String enqueueEquip(String args) {
        if (args.isEmpty()) {
            return "usage: agent equip <item> (requires an open container)";
        }
        QUEUE.injectInterrupt(new EquipAction(args));
        return "queued equip " + args;
    }

    /** {@code sneak|sprint [on|off]} -- empty arg means on. */
    private static String enqueueToggle(String which, String args) {
        String a = args.trim().toLowerCase(Locale.ROOT);
        boolean on;
        if (a.isEmpty() || a.equals("on")) {
            on = true;
        } else if (a.equals("off")) {
            on = false;
        } else {
            return "usage: agent " + which + " [on|off]";
        }
        KeyInputAction.Mode m = which.equals("sneak")
                ? (on ? KeyInputAction.Mode.SNEAK_ON : KeyInputAction.Mode.SNEAK_OFF)
                : (on ? KeyInputAction.Mode.SPRINT_ON : KeyInputAction.Mode.SPRINT_OFF);
        QUEUE.injectInterrupt(new KeyInputAction(m));
        return "queued " + which + " " + (on ? "on" : "off");
    }

    /** {@code attack [nearest|<id>|crosshair] [crit|normal|sweep|ranged]}. Defaults: nearest, crit. */
    private static String enqueueAttack(String args) {
        String spec = "nearest";
        String mode = "crit";
        if (!args.isEmpty()) {
            for (String tok : args.split("\\s+")) {
                String low = tok.toLowerCase(Locale.ROOT);
                if (low.equals("crit") || low.equals("ranged")) {
                    mode = low;
                } else if (low.equals("normal") || low.equals("nocrit") || low.equals("sweep")) {
                    mode = "normal";
                } else {
                    spec = tok;
                }
            }
        }
        if (spec.equalsIgnoreCase("crosshair")) {
            spec = "";
        }
        QUEUE.injectInterrupt(new AttackAction(spec, mode));
        return "queued attack (target=" + (spec.isEmpty() ? "crosshair" : spec) + ", mode=" + mode + ")";
    }

    /** {@code follow <player> [dist]} (default dist 3). Runs until 'stop'. */
    private static String enqueueFollow(String args) {
        String[] t = args.split("\\s+");
        if (args.isEmpty() || t[0].isEmpty()) {
            return "usage: agent follow <player> [dist]";
        }
        String who = t[0];
        double dist = 3.0;
        if (t.length >= 2) {
            try {
                dist = Double.parseDouble(t[1]);
            } catch (NumberFormatException e) {
                return "usage: agent follow <player> [dist]";
            }
        }
        QUEUE.append(new FollowAction(who, dist));
        return "queued follow " + who + " (keep within " + (int) dist + "m; 'stop' to end)";
    }

    /**
     * {@code drop [hand|hb1-9|inv N|head|chest|legs|feet|offhand|<name>] [n|all]} -- the Q-key
     * equivalent (703a gap). Bare integers are a COUNT; "inv N" is a slot. Default: 1 from hand.
     */
    private static String enqueueDrop(String args) {
        String a = (args == null) ? "" : args.trim();
        String[] t = a.isEmpty() ? new String[0] : a.split("\\s+");
        int count = 1;
        StringBuilder spec = new StringBuilder();
        for (int i = 0; i < t.length; i++) {
            String tok = t[i];
            if (tok.equalsIgnoreCase("all")) {
                count = DropAction.ALL;
                continue;
            }
            boolean numeric = !tok.isEmpty() && tok.chars().allMatch(Character::isDigit);
            if (numeric && !(i > 0 && t[i - 1].equalsIgnoreCase("inv"))) {
                count = Integer.parseInt(tok);
                if (count < 1) {
                    return "usage: drop [slot|<item>] [n|all] (n >= 1)";
                }
                continue;
            }
            if (spec.length() > 0) {
                spec.append(' ');
            }
            spec.append(tok);
        }
        QUEUE.injectInterrupt(new DropAction(spec.toString(), count));
        return "queued drop " + (spec.length() == 0 ? "held item" : "'" + spec + "'")
                + (count == DropAction.ALL ? " (whole stack)" : " x" + count);
    }

    /** {@code shield [holdTicks]} (default 40t = 2s). */
    private static String enqueueShield(String args) {
        int ticks = 40;
        if (!args.isEmpty()) {
            try {
                ticks = Integer.parseInt(args.trim());
            } catch (NumberFormatException e) {
                return "usage: agent shield [holdTicks]";
            }
        }
        QUEUE.injectInterrupt(new ShieldAction(ticks));
        return "queued shield (hold " + ticks + "t)";
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
