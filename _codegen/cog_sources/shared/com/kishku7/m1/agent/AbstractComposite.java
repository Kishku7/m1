package com.kishku7.m1.agent;

/**
 * Convenience base for COMPOSITE actions (goals). A composite never touches the world; on each
 * step it decides what to do next and {@link ActionContext#push}es child actions, then reports
 * whether it is still working ({@link StepResult#RUNNING}), has met its goal
 * ({@link StepResult#DONE}), or cannot proceed ({@link StepResult#FAILED}).
 *
 * <p>Expansion is LAZY by contract: read the world fresh in {@link #expand} every step, never
 * pre-plan a fixed list of primitives. That is what lets a goal absorb a world changed by an
 * interrupt and simply carry on.
 */
public abstract class AbstractComposite implements MinecraftAction {

    @Override
    public final Kind kind() {
        return Kind.COMPOSITE;
    }

    @Override
    public final StepResult step(ActionContext ctx) {
        return expand(ctx);
    }

    /** Re-read the world, push the next child(ren), and report progress. */
    protected abstract StepResult expand(ActionContext ctx);
}
