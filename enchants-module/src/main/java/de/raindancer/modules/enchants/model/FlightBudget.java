package de.raindancer.modules.enchants.model;

/** How much flight a player has left, in milliseconds. Plain data: no server, no clock. */
public final class FlightBudget {

    private long remaining;
    private boolean known;

    public static FlightBudget of(long remainingMillis) {
        FlightBudget budget = new FlightBudget();
        budget.remaining = Math.max(0, remainingMillis);
        budget.known = true;
        return budget;
    }

    /** Whether it has ever been given a capacity, i.e. is worth saving. */
    public boolean isKnown() {
        return known;
    }

    public long remainingMillis() {
        return remaining;
    }

    /** Adopts the capacity of the current level: a first sight starts full, a lower level cuts what is left. */
    public void fitTo(long capacityMillis) {
        if (!known) {
            remaining = capacityMillis;
            known = true;
        } else if (remaining > capacityMillis) {
            remaining = capacityMillis;
        }
    }

    /** @return true only for the call that emptied it */
    public boolean drain(long millis) {
        if (remaining <= 0) {
            return false;
        }
        remaining = Math.max(0, remaining - Math.max(0, millis));
        return remaining == 0;
    }

    /** A full tank takes {@code rechargeMillis}. */
    public void recharge(long millis, long capacityMillis, long rechargeMillis) {
        if (rechargeMillis <= 0) {
            remaining = capacityMillis;
            return;
        }
        long gained = (long) ((double) capacityMillis * Math.max(0, millis) / rechargeMillis);
        remaining = Math.min(capacityMillis, remaining + gained);
    }
}
