package de.raindancer.modules.claims.model;

/** The share of a paid entry fee the server destroys instead of passing to the owners. */
public final class EntryFeeCut {

    private EntryFeeCut() {
    }

    /** How much of {@code quantity} is destroyed — rounded down, and never outside 0..quantity. */
    public static int destroyed(int quantity, double percent) {
        if (quantity <= 0 || !(percent > 0)) {
            return 0;
        }
        double share = Math.min(100.0D, percent) / 100.0D;
        return (int) Math.min(quantity, Math.floor(quantity * share));
    }

    public static int kept(int quantity, double percent) {
        return Math.max(0, quantity) - destroyed(quantity, percent);
    }
}
