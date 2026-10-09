package de.raindancer.modules.performance.model;

/** The last samples of the server thread, each with when it was taken. One writer, any reader. */
public final class SampleRing {

    private final long[] times;
    private final Cause[] causes;
    private int next;
    private int size;

    public SampleRing(int capacity) {
        this.times = new long[capacity];
        this.causes = new Cause[capacity];
    }

    public synchronized void add(long nanoTime, Cause cause) {
        times[next] = nanoTime;
        causes[next] = cause;
        next = (next + 1) % times.length;
        size = Math.min(size + 1, times.length);
    }

    public synchronized int size() {
        return size;
    }

    /** The samples taken from {@code fromNanos} to {@code toNanos}, both included — one slow tick, usually. */
    public synchronized Attribution between(long fromNanos, long toNanos) {
        Attribution attribution = new Attribution();
        for (int i = 0; i < size; i++) {
            if (times[i] >= fromNanos && times[i] <= toNanos) {
                attribution.count(causes[i]);
            }
        }
        return attribution;
    }

    public synchronized Attribution all() {
        Attribution attribution = new Attribution();
        for (int i = 0; i < size; i++) {
            attribution.count(causes[i]);
        }
        return attribution;
    }
}
