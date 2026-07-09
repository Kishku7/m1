package com.kishku7.m1.agent;

import java.util.List;

/**
 * Seam for the reactive sensor layer. Each tick the {@link AgentLoop} polls the registered
 * source for high-priority interrupt actions to inject (combat-evade, shield-up, flee-lava).
 * Normally returns an empty list. The Minecraft-coupled sensor manager implements this in
 * {@code shared_minecraft}; the core stays MC-agnostic.
 *
 * <p>This poll is part of the PROTECTED FLOOR -- it runs every tick regardless of frame budget,
 * so the agent is always ready to be interrupted.
 */
public interface InterruptSource {

    /** Reflex interrupts to raise this tick; an empty list when nothing is threatening. */
    List<MinecraftAction> poll(ActionContext ctx);
}
