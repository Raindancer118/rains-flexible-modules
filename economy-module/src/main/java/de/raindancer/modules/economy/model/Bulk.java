package de.raindancer.modules.economy.model;

import java.util.List;

/**
 * How much cheaper one item gets when bought in quantity.
 *
 * @param tiers from how many on, how many percent off; ascending
 * @param most  the most it ever comes off, whatever the tiers say
 */
public record Bulk(List<Tier> tiers, int most) {

    public static final Bulk NONE = new Bulk(List.of(), 0);

    public record Tier(int from, int percent) {
    }

    public Bulk {
        tiers = List.copyOf(tiers);
        most = Math.max(0, most);
    }

    public boolean applies() {
        return most > 0 && !tiers.isEmpty();
    }

    /** Percent off for buying {@code count} at once. */
    public int percentFor(int count) {
        int percent = 0;
        for (Tier tier : tiers) {
            if (count >= tier.from()) {
                percent = Math.max(percent, tier.percent());
            }
        }
        return Math.min(percent, most);
    }

    /** The first tier, for "5% off from 128". */
    public Tier first() {
        return tiers.getFirst();
    }
}
