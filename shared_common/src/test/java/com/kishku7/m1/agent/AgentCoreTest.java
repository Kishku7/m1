package com.kishku7.m1.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless behavior tests for the MC-agnostic agent core. No Minecraft, no gradle, no JUnit -- a
 * self-contained harness compiled and run with a plain JDK. It pins the execution policy the
 * skeleton left minimal: queue semantics (append / inject / replace), interrupt preempt with
 * pause/resume, composite lazy expansion, report sequencing, and the budgeter.
 *
 * <p>Run: javac the agent package + this file, then {@code java com.kishku7.m1.agent.AgentCoreTest}.
 * Exits non-zero if any check fails.
 *
 * <p>(Interim: lives in src/test but is run standalone; wiring a gradle test source-set into the
 * multi-loader build is a later step -- design doc section 12.)
 */
public final class AgentCoreTest {

    private AgentCoreTest() {
    }

    // ----- test doubles ----------------------------------------------------- //

    /** A leaf that records its id each step, finishing after {@code steps} steps. */
    private static final class Leaf implements MinecraftAction {
        private final String id;
        private final List<String> log;
        private int remaining;

        Leaf(String id, int steps, List<String> log) {
            this.id = id;
            this.remaining = steps;
            this.log = log;
        }

        @Override
        public String name() {
            return id;
        }

        @Override
        public StepResult step(ActionContext ctx) {
            log.add(id);
            return (--remaining <= 0) ? StepResult.DONE : StepResult.RUNNING;
        }

        @Override
        public void onInterrupted(ActionContext ctx) {
            log.add(id + ".int");
        }

        @Override
        public void onResumed(ActionContext ctx) {
            log.add(id + ".res");
        }
    }

    /** A leaf that throws on step, to exercise the loop's failure path. */
    private static final class Thrower implements MinecraftAction {
        private final String id;

        Thrower(String id) {
            this.id = id;
        }

        @Override
        public String name() {
            return id;
        }

        @Override
        public StepResult step(ActionContext ctx) {
            throw new RuntimeException("boom");
        }
    }

    /** A composite that lazily pushes one child leaf per step, then completes. */
    private static final class Goal extends AbstractComposite {
        private final String id;
        private final String[] children;
        private final List<String> log;
        private int i;

        Goal(String id, String[] children, List<String> log) {
            this.id = id;
            this.children = children.clone();
            this.log = log;
        }

        @Override
        public String name() {
            return id;
        }

        @Override
        protected StepResult expand(ActionContext ctx) {
            if (i < children.length) {
                ctx.push(new Leaf(children[i], 1, log));
                i++;
                return StepResult.RUNNING;
            }
            log.add(id + ".done");
            return StepResult.DONE;
        }
    }

    /** Interrupt source that raises scripted interrupts on specific ticks. */
    private static final class Scripted implements InterruptSource {
        private final Map<Long, List<MinecraftAction>> byTick;

        Scripted(Map<Long, List<MinecraftAction>> byTick) {
            this.byTick = byTick;
        }

        @Override
        public List<MinecraftAction> poll(ActionContext ctx) {
            List<MinecraftAction> l = byTick.get(ctx.tick());
            return (l == null) ? Collections.<MinecraftAction>emptyList() : l;
        }
    }

    /** Interrupt source that never raises anything. */
    private static final class NoInterrupts implements InterruptSource {
        @Override
        public List<MinecraftAction> poll(ActionContext ctx) {
            return Collections.emptyList();
        }
    }

    // ----- harness ---------------------------------------------------------- //

    private static int check(boolean cond, String msg) {
        if (!cond) {
            System.out.println("  FAIL: " + msg);
            return 1;
        }
        return 0;
    }

    private static AgentLoop loop(ActionQueue q, ReportChannel rc, InterruptSource src) {
        return new AgentLoop(q, rc, new Budgeter(10.0), src);
    }

    private static void tick(AgentLoop loop, int n) {
        for (int i = 0; i < n; i++) {
            loop.tick(null);
        }
    }

    // ----- tests ------------------------------------------------------------ //

    private static int testSequential() {
        int f = 0;
        List<String> log = new ArrayList<>();
        ActionQueue q = new ActionQueue();
        AgentLoop loop = loop(q, new ReportChannel(), new NoInterrupts());
        q.append(new Leaf("A", 1, log));
        q.append(new Leaf("B", 1, log));
        tick(loop, 3);
        f += check(log.equals(Arrays.asList("A", "B")), "sequential order, got " + log);
        f += check(q.isIdle(), "idle after sequential, backlog=" + q.backlogSize());
        return f;
    }

    private static int testMultiStep() {
        int f = 0;
        List<String> log = new ArrayList<>();
        ActionQueue q = new ActionQueue();
        AgentLoop loop = loop(q, new ReportChannel(), new NoInterrupts());
        q.append(new Leaf("A", 3, log));
        tick(loop, 3);
        f += check(log.equals(Arrays.asList("A", "A", "A")), "multi-step, got " + log);
        f += check(q.isIdle(), "idle after multi-step");
        return f;
    }

    private static int testInterruptPreemptAndResume() {
        int f = 0;
        List<String> log = new ArrayList<>();
        ActionQueue q = new ActionQueue();
        Map<Long, List<MinecraftAction>> script = new HashMap<>();
        script.put(2L, Arrays.<MinecraftAction>asList(new Leaf("I", 1, log)));
        AgentLoop loop = loop(q, new ReportChannel(), new Scripted(script));
        q.append(new Leaf("A", 3, log));
        tick(loop, 5);
        // A steps at t1, t3, t4; interrupt I preempts at t2 with pause/resume notifications.
        f += check(log.equals(Arrays.asList("A", "A.int", "I", "A.res", "A", "A")),
                "interrupt preempt/resume, got " + log);
        f += check(q.isIdle(), "idle after interrupt test");
        return f;
    }

    private static int testReplace() {
        int f = 0;
        List<String> log = new ArrayList<>();
        ActionQueue q = new ActionQueue();
        AgentLoop loop = loop(q, new ReportChannel(), new NoInterrupts());
        q.append(new Leaf("A", 5, log));
        tick(loop, 1); // A starts (mid-flight)
        q.replace(Arrays.<MinecraftAction>asList(new Leaf("B", 1, log)));
        tick(loop, 2);
        f += check(log.equals(Arrays.asList("A", "B")), "replace drops A, runs B, got " + log);
        f += check(q.isIdle(), "idle after replace");
        return f;
    }

    private static int testCompositeLazyExpansion() {
        int f = 0;
        List<String> log = new ArrayList<>();
        ActionQueue q = new ActionQueue();
        AgentLoop loop = loop(q, new ReportChannel(), new NoInterrupts());
        q.append(new Goal("G", new String[] {"C1", "C2", "C3"}, log));
        tick(loop, 7);
        f += check(log.equals(Arrays.asList("C1", "C2", "C3", "G.done")),
                "composite lazy expansion order, got " + log);
        f += check(q.isIdle(), "idle after composite");
        return f;
    }

    private static int testFailurePathReports() {
        int f = 0;
        ActionQueue q = new ActionQueue();
        ReportChannel rc = new ReportChannel();
        AgentLoop loop = loop(q, rc, new NoInterrupts());
        q.append(new Thrower("X"));
        tick(loop, 2);
        List<Report> reports = rc.drain();
        f += check(reports.size() == 1, "one failure report, got " + reports.size());
        f += check(!reports.isEmpty() && reports.get(0).text().contains("X failed"),
                "failure report mentions action, got " + reports);
        f += check(q.isIdle(), "idle after failed action popped");
        return f;
    }

    private static int testReportSequencing() {
        int f = 0;
        ReportChannel rc = new ReportChannel();
        long s1 = rc.emit(ReportClass.STATUS, "a", 1);
        long s2 = rc.emit(ReportClass.ADVISORY, "b", 1);
        long s3 = rc.emit(ReportClass.ESCALATION, "c", 2);
        f += check(s1 == 1 && s2 == 2 && s3 == 3, "monotonic seq, got " + s1 + "," + s2 + "," + s3);
        f += check(rc.lastSeq() == 3, "lastSeq, got " + rc.lastSeq());
        List<Report> d = rc.drain();
        f += check(d.size() == 3, "drain size, got " + d.size());
        f += check(d.get(0).seq() == 1 && d.get(2).seq() == 3, "drain ordered oldest-first");
        f += check(d.get(2).cls() == ReportClass.ESCALATION, "report class preserved");
        f += check(rc.drain().isEmpty(), "drain empties the channel");
        return f;
    }

    private static int testBudgeter() {
        int f = 0;
        Budgeter b = new Budgeter(10.0); // 10 ms target
        f += check(b.hasHeadroomForDiscretionary(), "headroom when idle");
        b.recordTickCost(5_000_000L); // 5 ms
        f += check(b.hasHeadroomForDiscretionary(), "headroom under target");
        for (int i = 0; i < 25; i++) {
            b.recordTickCost(50_000_000L); // 50 ms, sustained over budget
        }
        f += check(!b.hasHeadroomForDiscretionary(), "no headroom over target, avg=" + b.avgMillis());
        return f;
    }

    // ----- main ------------------------------------------------------------- //

    public static void main(String[] args) {
        int fails = 0;
        fails += testSequential();
        fails += testMultiStep();
        fails += testInterruptPreemptAndResume();
        fails += testReplace();
        fails += testCompositeLazyExpansion();
        fails += testFailurePathReports();
        fails += testReportSequencing();
        fails += testBudgeter();

        if (fails > 0) {
            System.out.println("AGENT CORE TESTS FAILED: " + fails + " check(s)");
            System.exit(1);
        }
        System.out.println("AGENT CORE TESTS PASSED (8 cases)");
    }
}
