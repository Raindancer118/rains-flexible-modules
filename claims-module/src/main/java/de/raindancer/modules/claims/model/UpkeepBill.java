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

    /**
     * What one claim costs on its own: {@code perClaim * (1 + areaPercent/100)^(chunks - 1)}, so a claim of
     * one chunk pays the plain fee and every further chunk inside the same claim makes that claim dearer.
     */
    public static Money claimFee(Money perClaim, double areaPercent, int chunks) {
        if (perClaim == null || !perClaim.isPositive()) {
            return Money.ZERO;
        }
        double ratio = 1.0D + Math.max(0.0D, areaPercent) / 100.0D;
        return Money.of(clamp(perClaim.minor() * Math.pow(ratio, Math.max(1, chunks) - 1)));
    }

    /** The per-claim fees of all of an owner's claims, given how many chunks each covers. */
    public static Money claimFees(Money perClaim, double areaPercent, java.util.Collection<Integer> chunksPerClaim) {
        long total = 0L;
        for (int chunks : chunksPerClaim) {
            total = Math.min((long) CEILING, total + claimFee(perClaim, areaPercent, chunks).minor());
        }
        return Money.of(total);
    }

    /** The bill after an owner's reduction: {@code payPercent} of it, clamped to 0..100. */
    public static Money discounted(Money bill, double payPercent) {
        if (bill == null || !bill.isPositive()) {
            return Money.ZERO;
        }
        double percent = Double.isNaN(payPercent) ? 100.0D : Math.max(0.0D, Math.min(100.0D, payPercent));
        return Money.of(Math.round(bill.minor() * percent / 100.0D));
    }

    private static long clamp(double value) {
        if (Double.isNaN(value) || value <= 0) {
            return 0L;
        }
        return Math.round(Math.min(value, CEILING));
    }
}
