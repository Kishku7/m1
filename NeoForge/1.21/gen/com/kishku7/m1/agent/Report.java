package com.kishku7.m1.agent;

/**
 * One outbound message to the AI. Immutable. Carries a monotonically increasing sequence number
 * so the AI can detect a gap (and thus that it has drifted and should request a full update).
 * The sequence number is assigned by {@link ReportChannel}.
 */
public final class Report {

    private final long seq;
    private final ReportClass cls;
    private final String text;
    private final long tick;

    public Report(long seq, ReportClass cls, String text, long tick) {
        this.seq = seq;
        this.cls = cls;
        this.text = text;
        this.tick = tick;
    }

    public long seq() {
        return seq;
    }

    public ReportClass cls() {
        return cls;
    }

    public String text() {
        return text;
    }

    public long tick() {
        return tick;
    }

    @Override
    public String toString() {
        return "[" + seq + "/" + cls + "@" + tick + "] " + text;
    }
}
