package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

/**
 * What the shop will do with one item, at today's prices.
 *
 * @param value    what it is worth before markups and supply and demand
 * @param buy      what one costs a player, when {@code buyable}
 * @param sell     what the shop pays for one, when {@code sellable}
 * @param source   where the value came from
 */
public record PriceTag(String material, Money value, Money buy, Money sell, boolean buyable, boolean sellable,
                       Source source) {

    public enum Source {
        /** The raw-material price list. */
        BASE,
        /** Worked out from the server's recipes. */
        RECIPE,
        /** Set by the owner. */
        CUSTOM,
        /** No price at all. */
        NONE
    }

    public static PriceTag unpriced(String material) {
        return new PriceTag(material, Money.ZERO, Money.ZERO, Money.ZERO, false, false, Source.NONE);
    }

    /** The same, but not sold: the shop still buys it, if it did. */
    public PriceTag notSold() {
        return new PriceTag(material, value, buy, sell, false, sellable, source);
    }

    public boolean tradable() {
        return buyable || sellable;
    }
}
