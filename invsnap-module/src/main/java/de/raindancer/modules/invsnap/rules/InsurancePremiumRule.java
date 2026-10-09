package de.raindancer.modules.invsnap.rules;

import de.raindancer.core.social.economy.Money;

/** What one insured death costs: a percent of the inventory's value plus a flat price, capped. */
public final class InsurancePremiumRule implements IInvSnapRule {

    /**
     * @param inventoryValue what the inventory was worth at death
     * @param percent        share of that value, 0 for none
     * @param flat           added on top, zero for none
     * @param most           the cap on the total; zero (or less) means no cap
     */
    public Money premium(Money inventoryValue, double percent, Money flat, Money most) {
        Money value = inventoryValue == null ? Money.ZERO : inventoryValue;
        Money total = value.share(percent / 100.0);
        if (flat != null && flat.isPositive()) {
            total = total.plus(flat);
        }
        if (most != null && most.isPositive() && total.isMoreThan(most)) {
            total = most;
        }
        return total.isNegative() ? Money.ZERO : total;
    }

    @Override
    public String describe() {
        return "the premium for an insured death: a percent of the inventory's value plus a flat price, capped";
    }
}
