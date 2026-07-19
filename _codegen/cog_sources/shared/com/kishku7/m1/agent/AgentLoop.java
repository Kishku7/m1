package com.kishku7.m1.agent;

import java.util.List;

/**
 * The agent heartbeat. {@link #tick} is called once per client tick by the Minecraft-coupled layer
 * (behind the Platform/Compat facade). It does only cheap work; heavy work is offloaded to a
 * {@link WorkerPool} and polled on later ticks, which is what keeps every tick responsive enough to
 * honor interrupts.
 *
 * <p>Per tick, in order:
 * <ol>
 *   <li>PROTECTED FLOOR: poll the {@link InterruptSource} and inject any reflex interrupts. Runs
 *       every tick regardless of budget.</li>
 *   <li>Select the current action (interrupt &gt; active task &gt; next backlog task) and step it
 *       once, measuring its cost.</li>
 *   <li>Fold the cost into the {@link Budgeter}. Discretionary offloaded work is gated by the
 *       budgeter ({@link #mayRunDiscretionary}); the step itself is part of the protected floor.</li>
 * </ol>
 *
 * <p>Single-threaded by contract: only the client-tick thread calls {@link #tick}. The
 * {@link ActionQueue} and {@link ReportChannel} it touches are themselves thread-safe for the
 * socket thread's concurrent access.
 */
public final class AgentLoop {

    private final ActionQueue queue;
    private final ReportChannel reports;
    private final Budgeter budgeter;
    private final InterruptSource interrupts;

    private long tickCounter;

    public AgentLoop(ActionQueue queue, ReportChannel reports, Budgeter budgeter,
                     InterruptSource interrupts) {
        this.queue = queue;
        this.reports = reports;
        this.budgeter = budgeter;
        this.interrupts = interrupts;
    }

    /**
     * Advance the agent by one client tick. {@code services} is the MC-coupled service locator
     * (perception/actuation), or {@code null} during headless tests.
     */
    public void tick(ServiceLocator services) {
        long t = ++tickCounter;
        ActionContext ctx = new LoopContext(t, services);
        long start = System.nanoTime();

        // 1. Protected floor: reflex sensing always runs.
        List<MinecraftAction> raised = interrupts.poll(ctx);
        for (MinecraftAction interrupt : raised) {
            queue.injectInterrupt(interrupt);
        }

        // 2. Step the current action once.
        MinecraftAction current = queue.selectCurrent(ctx);
        if (current != null) {
            StepResult result;
            try {
                result = current.step(ctx);
            } catch (RuntimeException e) {
                result = StepResult.FAILED;
                reports.emit(ReportClass.STATUS, "action " + current.name() + " failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage(), t);
            }
            queue.completeCurrent(result, ctx);
        }

        // 3. Account this tick's engine cost.
        budgeter.recordTickCost(System.nanoTime() - start);
    }

    /**
     * Whether there is room for discretionary offloaded work this tick (delegates to the budgeter;
     * reflexes and stepping ignore this and always run).
     */
    public boolean mayRunDiscretionary() {
        return budgeter.hasHeadroomForDiscretionary();
    }

    public long currentTick() {
        return tickCounter;
    }

    // ----------------------------------------------------------------------- //

    /** Per-tick {@link ActionContext} backed by this loop's queue and report channel. */
    private final class LoopContext implements ActionContext {

        private final long tick;
        private final ServiceLocator services;

        LoopContext(long tick, ServiceLocator services) {
            this.tick = tick;
            this.services = services;
        }

        @Override
        public long tick() {
            return tick;
        }

        @Override
        public void push(MinecraftAction child) {
            queue.pushChild(child);
        }

        @Override
        public void report(ReportClass cls, String text) {
            reports.emit(cls, text, tick);
        }

        @Override
        public <T> T service(Class<T> type) {
            return services == null ? null : services.lookup(type);
        }
    }
}
