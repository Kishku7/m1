package com.kishku7.m1.agent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offload pool for heavy, non-blocking work -- pathfinding search, traversability-graph updates,
 * digest building, screenshot encode. The tick loop submits a job and polls its {@link Future} on
 * a later tick; it NEVER blocks waiting. Offloading is precisely what keeps the tick loop cheap
 * enough to evaluate sensors every tick (the always-interruptible guarantee).
 *
 * <p>Daemon threads, so the pool never holds the JVM alive.
 */
public final class WorkerPool {

    private final ExecutorService pool;

    public WorkerPool(int threads) {
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "m1-agent-worker-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** Submit a job; poll the returned future from the tick thread, never block on it. */
    public <T> Future<T> submit(Callable<T> job) {
        return pool.submit(job);
    }

    /** Stop the pool (on mod/agent shutdown). */
    public void shutdown() {
        pool.shutdownNow();
    }
}
