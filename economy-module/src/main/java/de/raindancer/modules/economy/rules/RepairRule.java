package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

import java.util.Optional;

/** What /repair costs. */
public final class RepairRule implements IEconomyRule {

    /**
     * @param worth   what the item is worth in the shop, if it is priced at all
     * @param damage  how worn it is; zero for nothing to repair
     * @param percent a full repair's price, in percent of the worth
     * @return empty when there is nothing to repair
     */
    public Optional<Money> price(Optional<Money> worth, int damage, int maxDurability, double percent, Money least) {
        if (damage <= 0 || maxDurability <= 0) {
            return Optional.empty();
        }
        Money floor = least.max(Money.of(1));
        if (worth.isEmpty() || !(percent > 0)) {
            return Optional.of(floor);
        }
        double worn = Math.min(1.0, (double) damage / maxDurability);
        long minor = (long) Math.ceil(worth.get().minor() * percent / 100.0 * worn);
        return Optional.of(Money.of(minor).max(floor));
    }

    @Override
    public String describe() {
        return "what repairing an item costs";
    }
}
