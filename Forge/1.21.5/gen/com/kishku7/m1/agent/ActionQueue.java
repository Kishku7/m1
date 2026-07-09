package com.kishku7.m1.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The command queue and interrupt state machine -- the standing plan plus the reactive override,
 * with the three AI-facing semantics (append / inject / replace) and the priority model from the
 * design.
 *
 * <p>Three regions, highest priority first:
 * <ol>
 *   <li><b>interrupt stack</b> -- reflex overrides (combat-evade, flee-lava). LIFO. Preempts
 *       everything. Injected by the sensor layer via {@link #injectInterrupt}.</li>
 *   <li><b>active stack</b> -- the in-progress standing-plan task and its lazy expansion frames
 *       (a composite's children sit above it). The top frame is what runs.</li>
 *   <li><b>backlog</b> -- queued standing-plan tasks not yet started (the optimistic pipeline);
 *       {@link #append}ed by the AI ahead of time.</li>
 * </ol>
 *
 * <p>Thread-safe: the socket thread mutates it (append / inject / replace) while the tick thread
 * drives it (selectCurrent / completeCurrent). All access is synchronized on this instance.
 *
 * <p>NOTE (skeleton): the precise failure-propagation policy for nested composites is intentionally
 * minimal here and will be pinned by the headless unit tests in the next increment (design doc
 * section 3 -- lazy decomposition; section 12 -- open items).
 */
public final class ActionQueue {

    private final Deque<MinecraftAction> interruptStack = new ArrayDeque<>();
    private final Deque<MinecraftAction> activeStack = new ArrayDeque<>();
    private final Deque<MinecraftAction> backlog = new ArrayDeque<>();

    private boolean mainPaused;

    // --- AI-facing mutators (socket thread) --------------------------------- //

    /** Optimistic pipeline: queue a task to run after the current plan. */
    public synchronized void append(MinecraftAction task) {
        backlog.addLast(task);
    }

    /** Reflex override: preempt whatever is running with a high-priority action. */
    public synchronized void injectInterrupt(MinecraftAction action) {
        interruptStack.push(action);
    }

    /**
     * "Stop -- new plan": drop the standing plan (active task + backlog) and install a new one.
     * Active reflex interrupts are left to finish.
     */
    public synchronized void replace(List<MinecraftAction> tasks) {
        activeStack.clear();
        backlog.clear();
        mainPaused = false;
        backlog.addAll(tasks);
    }

    // --- composite expansion (tick thread, during a step) ------------------- //

    /** Push a child ahead of its parent on the currently active stack (lazy expansion). */
    public synchronized void pushChild(MinecraftAction child) {
        activeRegion().push(child);
    }

    // --- tick-thread driving ------------------------------------------------ //

    /**
     * Choose the action to step this tick, applying interrupt pause/resume notifications. Returns
     * {@code null} when there is nothing to do (idle).
     */
    public synchronized MinecraftAction selectCurrent(ActionContext ctx) {
        if (!interruptStack.isEmpty()) {
            if (!activeStack.isEmpty() && !mainPaused) {
                activeStack.peek().onInterrupted(ctx);
                mainPaused = true;
            }
            return interruptStack.peek();
        }
        // no interrupts pending
        if (!activeStack.isEmpty()) {
            if (mainPaused) {
                activeStack.peek().onResumed(ctx);
                mainPaused = false;
            }
            return activeStack.peek();
        }
        // start the next backlog task, if any
        MinecraftAction next = backlog.pollFirst();
        if (next != null) {
            activeStack.push(next);
        }
        return next;
    }

    /** Advance after stepping the current action with the given result. */
    public synchronized void completeCurrent(StepResult result, ActionContext ctx) {
        if (result == StepResult.RUNNING) {
            return;
        }
        // DONE or FAILED: pop the active region's top frame.
        Deque<MinecraftAction> region = activeRegion();
        if (!region.isEmpty()) {
            region.pop();
        }
    }

    /** The region whose top frame is currently the one being stepped. */
    private Deque<MinecraftAction> activeRegion() {
        return interruptStack.isEmpty() ? activeStack : interruptStack;
    }

    // --- introspection (mainly for tests) ----------------------------------- //

    public synchronized boolean isIdle() {
        return interruptStack.isEmpty() && activeStack.isEmpty() && backlog.isEmpty();
    }

    public synchronized int backlogSize() {
        return backlog.size();
    }

    public synchronized boolean hasInterrupt() {
        return !interruptStack.isEmpty();
    }
}
