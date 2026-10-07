package com.autism.seedcracker.motion.pure;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.autism.seedcracker.motion.pure.GridPathfinder.Status;
import com.autism.seedcracker.motion.pure.GridPathfinder.Step;

/**
 * Runs a {@link GridPathfinder.Search} on the shared planner thread. The world it searches must be
 * a snapshot (never the live client level). Poll {@link #done()} from the game thread; {@link #cancel()}
 * stops it at the next batch. A search that runs past its deadline ends as a partial path.
 */
public final class AsyncPlan {
    private static final int BATCH = 4096;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seedcracker-motion-planner");
        t.setDaemon(true);
        // Normal priority (was NORM-1): the render thread CPU-starves a lower-priority planner while it churns
        // on new-chunk meshes, which turned sub-second searches into multi-second wall-clock waits.
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    private final GridPathfinder.Search search;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean done;
    private volatile Status status = Status.RUNNING;
    private volatile List<Step> path = List.of();
    private volatile int expanded;
    private volatile Throwable error;

    private AsyncPlan(GridPathfinder.Search search) {
        this.search = search;
    }

    /**
     * Starts planning on the worker. After {@code softMs} a usable partial route is taken (the walk can
     * start while the next segment plans); {@code hardMs} stops the search no matter what.
     */
    public static AsyncPlan start(GridPathfinder.Search search, long softMs, long hardMs) {
        AsyncPlan p = new AsyncPlan(search);
        WORKER.execute(() -> p.run(softMs, hardMs));
        return p;
    }

    public static AsyncPlan start(GridPathfinder.Search search, long hardMs) {
        return start(search, hardMs, hardMs);
    }

    /** Same API on the calling thread (tests, or when the worker is unwanted). */
    public static AsyncPlan runNow(GridPathfinder.Search search, long softMs, long hardMs) {
        AsyncPlan p = new AsyncPlan(search);
        p.run(softMs, hardMs);
        return p;
    }

    public static AsyncPlan runNow(GridPathfinder.Search search, long hardMs) {
        return runNow(search, hardMs, hardMs);
    }

    private void run(long softMs, long hardMs) {
        long begin = System.nanoTime();
        long soft = begin + softMs * 1_000_000L, hard = begin + hardMs * 1_000_000L;
        try {
            Status s = Status.RUNNING;
            while (!cancelled.get()) {
                s = search.step(BATCH);
                expanded = search.expanded();
                if (s != Status.RUNNING) break;
                long now = System.nanoTime();
                if (now > hard || now > soft && search.hasPartial()) {
                    s = search.stopNow();
                    break;
                }
            }
            if (cancelled.get()) return;
            path = search.path();
            status = s;
        } catch (Throwable t) {
            error = t;
            status = Status.FAILED;
        } finally {
            done = true;
        }
    }

    public boolean done() {
        return done;
    }

    public Status status() {
        return status;
    }

    public List<Step> path() {
        return path;
    }

    public int expanded() {
        return expanded;
    }

    public Throwable error() {
        return error;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get();
    }
}
