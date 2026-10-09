package de.raindancer.modules.performance.model;

import java.util.Arrays;

/**
 * The last ticks' durations in milliseconds, and what they add up to. Written by the server thread
 * once a tick, read by whoever asks — so every method is synchronized; it is a few hundred doubles.
 */
public final class TickWindow {

    /** What a tick may take for the server to keep 20 a second. */
    public static final double BUDGET_MS = 50.0;

    private final double[] ticks;
    private int next;
    private int size;

    public TickWindow(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("a window needs room for at least one tick");
        }
        this.ticks = new double[capacity];
    }

    public synchronized void add(double millis) {
        ticks[next] = millis;
        next = (next + 1) % ticks.length;
        size = Math.min(size + 1, ticks.length);
    }

    public synchronized int size() {
        return size;
    }

    public synchronized double mean() {
        double sum = 0;
        for (int i = 0; i < size; i++) {
            sum += ticks[i];
        }
        return size == 0 ? 0 : sum / size;
    }

    public synchronized double worst() {
        double worst = 0;
        for (int i = 0; i < size; i++) {
            worst = Math.max(worst, ticks[i]);
        }
        return worst;
    }

    /** Nearest rank: the tick 95 % of the others were no slower than. */
    public synchronized double percentile95() {
        if (size == 0) {
            return 0;
        }
        double[] sorted = Arrays.copyOf(ticks, size);
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(0.95 * size) - 1];
    }

    /** Ticks a second at this pace — never more than 20, since the server waits out a fast tick. */
    public synchronized double tps() {
        double mean = mean();
        return mean <= BUDGET_MS ? 20.0 : 1000.0 / mean;
    }
}
