package de.raindancer.modules.economy.rules;

/** A new account may not send money away until it has been open for a while. */
public final class VestingRule implements IEconomyRule {

    /** Whole hours still to wait, rounded up; 0 when it may send. */
    public long hoursLeft(long openedAt, long now, int hours) {
        if (hours <= 0) {
            return 0;
        }
        long left = openedAt + hours * 3_600_000L - now;
        return left <= 0 ? 0 : (left + 3_599_999L) / 3_600_000L;
    }

    @Override
    public String describe() {
        return "whether a new account may send money away yet";
    }
}
