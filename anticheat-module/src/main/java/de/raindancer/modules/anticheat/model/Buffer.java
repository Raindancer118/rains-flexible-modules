package de.raindancer.modules.anticheat.model;

/**
 * Evidence that has to pile up before it counts. A failed sample adds, a clean one takes a little
 * away, and only a buffer past its limit becomes a flag — one odd tick is lag, ten are a cheat.
 */
public final class Buffer {

    private final double limit;
    private final double forgiveness;
    private double value;

    /**
     * @param limit       how much has to pile up before {@link #fail} answers true
     * @param forgiveness how much one clean sample takes away
     */
    public Buffer(double limit, double forgiveness) {
        this.limit = limit;
        this.forgiveness = forgiveness;
    }

    /** @return whether this failure pushed it past the limit */
    public synchronized boolean fail(double amount) {
        value = Math.min(value + Math.max(0, amount), limit * 4);
        return value > limit;
    }

    public synchronized void pass() {
        value = Math.max(0, value - forgiveness);
    }

    public synchronized double value() {
        return value;
    }

    public synchronized void reset() {
        value = 0;
    }
}
