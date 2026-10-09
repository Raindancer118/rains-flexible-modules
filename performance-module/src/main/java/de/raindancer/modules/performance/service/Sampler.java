package de.raindancer.modules.performance.service;

import de.raindancer.modules.performance.model.SampleRing;
import de.raindancer.modules.performance.rules.CauseRule;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Looks at the server thread's stack every few milliseconds and remembers what it was busy with — so a
 * spike can be explained after it happened, which is the only time anybody knows it did. Runs on its
 * own daemon thread, not a scheduler: a sample must be taken while the server thread is busy, which is
 * exactly when the server's own schedulers are not running.
 */
public final class Sampler implements AutoCloseable {

    private static final int DEPTH = 48;
    private static final long IDLE_CHECK_MS = 1000;

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final ScheduledExecutorService runner = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "RainsPerformance sampler");
        thread.setDaemon(true);
        return thread;
    });
    private final SampleRing ring;
    private final CauseRule causes;
    private final IntSupplier everyMs;
    private volatile long serverThread = -1;
    private volatile boolean closed;

    public Sampler(SampleRing ring, CauseRule causes, IntSupplier everyMs) {
        this.ring = ring;
        this.causes = causes;
        this.everyMs = everyMs;
    }

    /** The thread to sample, learnt from the first tick event — Bukkit has no handle on it. */
    public void serverThread(long id) {
        this.serverThread = id;
    }

    public void start() {
        runner.schedule(this::sampleAndReschedule, IDLE_CHECK_MS, TimeUnit.MILLISECONDS);
    }

    private void sampleAndReschedule() {
        if (closed) {
            return;
        }
        int every = everyMs.getAsInt();
        try {
            if (every > 0 && serverThread >= 0) {
                ThreadInfo info = threads.getThreadInfo(serverThread, DEPTH);
                if (info != null) {
                    List<String> frames = new ArrayList<>(info.getStackTrace().length);
                    for (StackTraceElement frame : info.getStackTrace()) {
                        frames.add(frame.getClassName() + "." + frame.getMethodName());
                    }
                    ring.add(System.nanoTime(), causes.causeOf(frames));
                }
            }
        } catch (RuntimeException failed) {
            // One bad sample is no reason to stop sampling.
        } finally {
            if (!closed) {
                runner.schedule(this::sampleAndReschedule, every > 0 ? every : IDLE_CHECK_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        runner.shutdownNow();
    }
}
