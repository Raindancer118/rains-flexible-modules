package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.PersonalPrice;
import de.raindancer.core.social.economy.PriceModifiers;
import de.raindancer.core.social.economy.TradeSide;

import java.util.Optional;

/**
 * What one player pays and is paid for an item, next to the shop's price for everybody.
 *
 * <p>The player's change is applied to a whole line — sixty-four of something — and rounded once. Rounded
 * per item, a quarter off a two-coin seed would round straight back up to two, and a cheap item's discount
 * would exist only on paper.
 *
 * @param shop       the price everybody sees
 * @param buyChange  what changes their buy price, and why
 * @param sellChange what changes their sell price, and why, before it is capped under their buy price
 */
public record YourPrice(PriceTag shop, PersonalPrice buyChange, PersonalPrice sellChange) {

    public static YourPrice same(PriceTag shop) {
        return new YourPrice(shop, PersonalPrice.unchanged(shop.buy()), PersonalPrice.unchanged(shop.sell()));
    }

    /** What {@code count} cost this player, or empty for a line too big to count. */
    public Optional<Money> buyFor(int count) {
        try {
            return Optional.of(PriceModifiers.scale(shop.buy().times(Math.max(0, count)), buyChange.percent(),
                    TradeSide.BUY));
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
    }

    /**
     * What the shop pays this player for {@code count}: their bonus applied, and kept a cent under what the
     * same line would cost them — buying and selling straight back must never earn.
     */
    public Optional<Money> sellFor(int count) {
        try {
            Money paid = PriceModifiers.scale(shop.sell().times(Math.max(0, count)), sellChange.percent(),
                    TradeSide.SELL);
            if (!shop.buyable() || !paid.isPositive()) {
                return Optional.of(paid);
            }
            Optional<Money> cost = buyFor(count);
            if (cost.isEmpty() || !paid.isAtLeast(cost.get())) {
                return Optional.of(paid);
            }
            return Optional.of(cost.get().minor() > 1 ? Money.of(cost.get().minor() - 1) : Money.ZERO);
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
