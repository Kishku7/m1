package com.kishku7.m1.agent;

/**
 * The per-step seam handed to every {@link MinecraftAction#step}. It is how an action reaches the
 * rest of the engine and (later) the Minecraft-coupled services -- without this package importing
 * any Minecraft type.
 *
 * <p>A leaf action does its work through {@link #service} (the perception/actuation facades
 * supplied by {@code shared_minecraft}; not yet defined -- design doc section 12). A composite
 * action touches nothing and instead {@link #push}es child actions, expanding lazily one step at
 * a time.
 */
public interface ActionContext {

    /** Monotonic client-tick counter at the time of this step. */
    long tick();

    /**
     * Enqueue a child action to run next (used by composites to expand lazily). The child is
     * placed ahead of its parent on the active stack, so a goal's expansion runs before the goal
     * continues and before whatever followed the goal in the plan.
     */
    void push(MinecraftAction child);

    /** Emit an outbound report to the AI (status / advisory / escalation). */
    void report(ReportClass cls, String text);

    /**
     * Look up a Minecraft-coupled service by type (perception, actuation, world map, ...).
     * Implementations live in {@code shared_minecraft}. Returns {@code null} if no provider is
     * registered. Kept as an open service-locator so this core need not name any MC API yet.
     */
    <T> T service(Class<T> type);
}
