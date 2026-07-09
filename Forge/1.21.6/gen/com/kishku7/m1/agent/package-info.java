/**
 * M1 agent core -- the MC-agnostic engine of the M1 agentic player.
 *
 * <p>This package contains ZERO Minecraft types by design. It is the reactive/deliberative
 * agent runtime: the action model (leaf vs composite), the command queue with its interrupt
 * stack, the tick scheduler, the adaptive budgeter, the offload worker pool, and the outbound
 * report channel. Everything Minecraft-coupled (sensors that read entities, leaves that issue
 * inputs, the client-tick hook) lives in {@code shared_minecraft} behind the Platform/Compat
 * facade and reaches this engine only through the seam interfaces declared here
 * ({@link com.kishku7.m1.agent.ActionContext}, {@link com.kishku7.m1.agent.InterruptSource},
 * {@link com.kishku7.m1.agent.ServiceLocator}).
 *
 * <p>Because it is pure Java it compiles and unit-tests headless, with no client boot.
 *
 * <p>Design: {@code projects/m1-agent.md}. Status: scaffolding (2026-06-29). Dev target during
 * the build phases is Fabric 26.1.2 only; other loaders/versions are ported at the end.
 */
package com.kishku7.m1.agent;
