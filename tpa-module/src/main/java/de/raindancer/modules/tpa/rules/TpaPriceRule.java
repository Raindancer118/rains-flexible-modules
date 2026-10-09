package de.raindancer.modules.tpa.rules;

import de.raindancer.core.social.economy.Money;

/**
 * What a teleport request's trip costs, as a pure sum.
 *
 * <p>The flat price always; within a world the distance adds in proportion, across worlds a flat extra
 * does instead, because a distance between two worlds' coordinate systems means nothing.
 */
public final class TpaPriceRule implements ITpaRule {

    /** Far beyond any border, so the sum below cannot overflow a long however it is set. */
    private static final double MOST_BLOCKS = 60_000_000d;

    public Money trip(Money flat, Money per100Blocks, Money crossWorld, boolean sameWorld, double blocks) {
        Money price = flat;
        if (!sameWorld) {
            return price.plus(crossWorld);
        }
        if (per100Blocks.isPositive() && blocks > 0 && !Double.isNaN(blocks)) {
            double clamped = Math.min(blocks, MOST_BLOCKS);
            price = price.plus(Money.of(Math.round(per100Blocks.minor() * clamped / 100d)));
        }
        return price;
    }

    @Override
    public String describe() {
        return "what a teleport request's trip costs";
    }
}
