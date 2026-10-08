package de.raindancer.modules.anticheat.model;

/**
 * How far a client's clock has run ahead of real time, in milliseconds.
 *
 * <p>Every client tick is worth 50 ms; real time between them is subtracted. A client running at
 * twenty ticks a second hovers around zero; one running faster climbs. Lag only ever pushes it down,
 * and how far down is capped — otherwise a player could stand still to bank credit and then spend it
 * all at double speed.
 */
public final class TimerBalance {

    public static final double TICK_MILLIS = 50;

    private final double lagCreditMillis;
    private long lastNanos = Long.MIN_VALUE;
    private double balance;

    public TimerBalance(double lagCreditMillis) {
        this.lagCreditMillis = Math.max(0, lagCreditMillis);
    }

    /** One client tick arrived at {@code nanos}. @return the balance after it */
    public synchronized double tick(long nanos) {
        if (lastNanos == Long.MIN_VALUE) {
            lastNanos = nanos;
            return balance;
        }
        double elapsed = (nanos - lastNanos) / 1_000_000.0;
        lastNanos = nanos;
        balance = Math.max(-lagCreditMillis, balance + TICK_MILLIS - elapsed);
        return balance;
    }

    /** After a flag: take back what was flagged, so the next flag needs fresh evidence. */
    public synchronized void settle() {
        balance = Math.min(balance, 0);
    }

    /**
     * Restarts the clock after a teleport. The balance stays: a player can teleport at will (an ender
     * pearl), so a teleport must neither wipe what was flag-worthy nor hand out fresh credit.
     */
    public synchronized void reset() {
        lastNanos = Long.MIN_VALUE;
    }

    /**
     * Starts over after a join, a respawn or a world change, with the full lag credit: the ticks a client
     * queued up while the server was busy loading it in arrive as one burst.
     */
    public synchronized void restart() {
        lastNanos = Long.MIN_VALUE;
        balance = -lagCreditMillis;
    }

    public synchronized double balance() {
        return balance;
    }
}
