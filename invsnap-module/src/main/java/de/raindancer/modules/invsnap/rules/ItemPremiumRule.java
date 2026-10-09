package de.raindancer.modules.invsnap.rules;

import de.raindancer.core.social.economy.Money;

/** What one item's policy costs per period: a percent of its value plus a flat price, never under a floor. */
public final class ItemPremiumRule implements IInvSnapRule {

    /**
     * @param itemValue what the item is worth, zero when it has no shop price
     * @param percent   share of that value, 0 for none
     * @param flat      added on top, zero for none
     * @param least     the floor; zero (or less) means none
     */
    public Money premium(Money itemValue, double percent, Money flat, Money least) {
        Money value = itemValue == null ? Money.ZERO : itemValue;
        Money total = percent > 0 ? value.share(percent / 100.0) : Money.ZERO;
        if (flat != null && flat.isPositive()) {
            total = total.plus(flat);
        }
        if (least != null && least.isPositive() && least.isMoreThan(total)) {
            total = least;
        }
        return total.isNegative() ? Money.ZERO : total;
    }

    @Override
    public String describe() {
        return "the premium of one insured item: a percent of its value plus a flat price, with a floor";
    }
}
