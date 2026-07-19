package com.kishku7.m1.agent;

/**
 * The single unit the AI directs and the engine executes, at ANY altitude. Two flavors:
 *
 * <ul>
 *   <li>{@link Kind#LEAF} -- touches the world (click, mine, place, move). Bottoms out in
 *       packets/inputs via {@link ActionContext#service}.</li>
 *   <li>{@link Kind#COMPOSITE} -- touches nothing; its {@link #step} emits child actions onto the
 *       queue, expanding LAZILY (re-reading the world each step) so it survives interrupts and a
 *       changed world. See {@link AbstractComposite}.</li>
 * </ul>
 *
 * <p>Actions are tick-stepped and resumable: {@link #step} does one slice and returns a
 * {@link StepResult}. Because state lives in the action instance, pause/resume across an interrupt
 * is free -- the engine simply stops calling {@link #step} and resumes later, with
 * {@link #onInterrupted}/{@link #onResumed} as optional notification hooks.
 */
public interface MinecraftAction {

    /** Whether an action does work itself (LEAF) or expands into children (COMPOSITE). */
    enum Kind { LEAF, COMPOSITE }

    /** Short stable identifier, e.g. {@code "mine"} or {@code "GatherWood"}. */
    String name();

    /** LEAF by default; composites override to {@link Kind#COMPOSITE}. */
    default Kind kind() {
        return Kind.LEAF;
    }

    /** Advance one tick's worth of work. */
    StepResult step(ActionContext ctx);

    /** Called once when a higher-priority interrupt preempts this action. Optional. */
    default void onInterrupted(ActionContext ctx) {
        // no-op by default
    }

    /** Called once when control returns to this action after an interrupt cleared. Optional. */
    default void onResumed(ActionContext ctx) {
        // no-op by default
    }

    /**
     * JSON parameter schema advertised to the AI in the capability registry. Placeholder for now
     * (design doc section 12 -- registry schema deferred); returns an empty schema.
     */
    default String paramSchema() {
        return "{}";
    }
}
