package de.raindancer.modules.performance.model;

/** One tick that took too long: when it ended ({@code System.nanoTime}), and how long it took. */
public record Spike(long endedNanos, double millis, int tick) {

    public long startedNanos() {
        return endedNanos - (long) (millis * 1_000_000);
    }
}
