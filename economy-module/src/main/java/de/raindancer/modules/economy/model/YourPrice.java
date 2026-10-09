package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.PersonalPrice;
import de.raindancer.core.social.economy.PriceModifiers;
import de.raindancer.core.social.economy.TradeSide;

import java.util.Optional;

/**
 * What one player pays and is paid for an item, next to the shop's price for everybody.
 *
 * <p>The player's change and the bulk discount are applied to a whole line — sixty-four of something — and
 * rounded once. Rounded per item, a quarter off a two-coin seed would round straight back up to two.
 *
 * @param shop       the price everybody sees
 * @param buyChange  what changes their buy price (a role), and why
 * @param sellChange what changes their sell price, and why, before it is capped
 * @param bulk       how much cheaper this item gets bought in quantity
 */
public record YourPrice(PriceTag shop, PersonalPrice buyChange, PersonalPrice sellChange, Bulk bulk) {

    public static YourPrice same(PriceTag shop) {
        return new YourPrice(shop, PersonalPrice.unchanged(shop.buy()), PersonalPrice.unchanged(shop.sell()),
                Bulk.NONE);
    }

    /** The whole change on buying {@code count}: their own plus bulk, as a negative percent for a discount. */
    public int buyPercentFor(int count) {
        return buyChange.percent() - bulk.percentFor(count);
    }

    /** What {@code count} cost this player, or empty for a line too big to count. */
    public Optional<Money> buyFor(int count) {
        return scaled(shop.buy(), count, buyPercentFor(count), TradeSide.BUY);
    }

    /**
     * What the shop pays this player for {@code count}: their bonus applied, and kept a cent under the cheapest
     * the same line could ever cost them — their own discount with the whole bulk discount, as if bought in
     * the largest quantity. Buying ten stacks cheap and selling them back a stack at a time must never earn.
     */
    public Optional<Money> sellFor(int count) {
        Optional<Money> paid = scaled(shop.sell(), count, sellChange.percent(), TradeSide.SELL);
        if (paid.isEmpty() || !shop.buyable() || !paid.get().isPositive()) {
            return paid;
        }
        Optional<Money> cheapest = scaled(shop.buy(), count, buyChange.percent() - bulk.most(), TradeSide.BUY);
        if (cheapest.isEmpty() || !paid.get().isAtLeast(cheapest.get())) {
            return paid;
        }
        return Optional.of(cheapest.get().minor() > 1 ? Money.of(cheapest.get().minor() - 1) : Money.ZERO);
    }

    private static Optional<Money> scaled(Money unit, int count, int percent, TradeSide side) {
        try {
            return Optional.of(PriceModifiers.scale(unit.times(Math.max(0, count)), percent, side));
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
    }

    public Money buy() {
        return buyFor(1).orElse(shop.buy());
    }

    public Money sell() {
        return sellFor(1).orElse(shop.sell());
    }

    public boolean buyChanged() {
        return buyChange.changed();
    }

    public boolean sellChanged() {
        return !sell().equals(shop.sell());
    }
}
