package com.kishku7.m1.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe outbound report queue. The agent loop (and actions) {@link #emit} reports here; the
 * socket thread {@link #drain}s and writes them to the AI. Fully decouples AI latency from
 * execution -- the socket thread may sit mid-write to a slow model while the loop keeps ticking.
 *
 * <p>Each report gets the next sequence number, so a consumer can spot a gap.
 */
public final class ReportChannel {

    private final Queue<Report> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong seq = new AtomicLong();

    /** Build, number, and enqueue a report. Returns the assigned sequence number. */
    public long emit(ReportClass cls, String text, long tick) {
        long n = seq.incrementAndGet();
        pending.add(new Report(n, cls, text, tick));
        return n;
    }

    /** Remove and return all currently pending reports, oldest first. */
    public List<Report> drain() {
        List<Report> out = new ArrayList<>();
        Report r;
        while ((r = pending.poll()) != null) {
            out.add(r);
        }
        return out;
    }

    /** Highest sequence number assigned so far (0 if none). */
    public long lastSeq() {
        return seq.get();
    }
}
