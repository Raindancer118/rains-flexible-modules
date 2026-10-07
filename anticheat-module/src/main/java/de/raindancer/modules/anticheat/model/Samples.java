package de.raindancer.modules.anticheat.model;

import java.util.Arrays;

/** A fixed-size ring of the most recent numbers, oldest overwritten first. */
public final class Samples {

    private final double[] values;
    private int next;
    private int size;

    public Samples(int capacity) {
        this.values = new double[Math.max(1, capacity)];
    }

    public synchronized void add(double value) {
        values[next] = value;
        next = (next + 1) % values.length;
        size = Math.min(size + 1, values.length);
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean full() {
        return size == values.length;
    }

    public synchronized void clear() {
        next = 0;
        size = 0;
    }

    /** Oldest first. */
    public synchronized double[] toArray() {
        double[] copy = new double[size];
        int start = (next - size + values.length) % values.length;
        for (int i = 0; i < size; i++) {
            copy[i] = values[(start + i) % values.length];
        }
        return copy;
    }

    /** The newest, or NaN. */
    public synchronized double last() {
        return size == 0 ? Double.NaN : values[(next - 1 + values.length) % values.length];
    }

    @Override
    public synchronized String toString() {
        return Arrays.toString(toArray());
    }
}
