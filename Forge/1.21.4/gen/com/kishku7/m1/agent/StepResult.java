package com.kishku7.m1.agent;

/**
 * Outcome of a single {@link MinecraftAction#step} call. Actions are tick-stepped, not
 * call-and-wait: each step does a small slice of work and reports where it now stands.
 */
public enum StepResult {

    /** More work remains; step me again next tick. */
    RUNNING,

    /** The action finished successfully; advance to the next one. */
    DONE,

    /** The action cannot proceed; advance and surface the failure. */
    FAILED
}
