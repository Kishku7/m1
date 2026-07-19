package com.kishku7.m1.agent;

/**
 * The three kinds of message the engine sends UP to the AI. The engine never blocks waiting on a
 * reply: STATUS and ADVISORY are fire-and-continue; only ESCALATION makes the agent idle at a
 * safe state pending input.
 */
public enum ReportClass {

    /** Informational; keeps the AI's replica current. No reply expected. Most traffic. */
    STATUS,

    /**
     * Telegraphed default the AI MAY override ("fleeing creeper unless you say otherwise");
     * carries the reflex's reasoning. Never blocks.
     */
    ADVISORY,

    /** A genuine ask ("boxed in" / "goal complete, what next?"). The only class that waits. */
    ESCALATION
}
