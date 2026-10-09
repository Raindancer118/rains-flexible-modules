package de.raindancer.modules.claims.model;

import de.raindancer.core.social.economy.Money;

/**
 * What holding land costs per billing period, as arithmetic and nothing else.
 *
 * <p>Progressive: the {@code n}-th chunk an owner holds (counted across all their claims) costs
 * {@code perChunk * (1 + growth/100)^(n-1)}, so the bill is a geometric series and a big landholder pays
 * proportionally more than a small one. With no growth it is flat.
 */
public final class UpkeepBill {

    /** Far below {@code Long.MAX_VALUE}, so adding it to an arrears balance can never overflow. */
    private static final double CEILING = 1.0e15;

    private UpkeepBill() {
    }

    /** The whole bill for holding {@code chunks} chunks. */
    public static Money of(Money perChunk, double growthPercent, int chunks) {
        if (perChunk == null || !perChunk.isPositive() || chunks <= 0) {
            return Money.ZERO;
        }
        double ratio = 1.0D + Math.max(0.0D, growthPercent) / 100.0D;
        double base = perChunk.minor();
        double total = ratio == 1.0D
                ? base * chunks
                : base * (Math.pow(ratio, chunks) - 1.0D) / (ratio - 1.0D);
        return Money.of(clamp(total));
    }

    /** What the {@code index}-th chunk (1-based) costs on its own. */
    public static Money chunkCost(Money perChunk, double growthPercent, int index) {
        if (perChunk == null || !perChunk.isPositive() || index <= 0) {
            return Money.ZERO;
        }
        double ratio = 1.0D + Math.max(0.0D, growthPercent) / 100.0D;
        return Money.of(clamp(perChunk.minor() * Math.pow(ratio, index - 1)));
    }

    private static long clamp(double value) {
        if (Double.isNaN(value) || value <= 0) {
            return 0L;
        }
        return Math.round(Math.min(value, CEILING));
    }
}
