package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.YourPrice;

import java.util.List;
import java.util.Optional;

/**
 * One player's price for an enchanted book, from the shop's price and their own changes (roles, the economy
 * lever, selling the same lately) — kept so that no way through the shop makes money:
 *
 * <ul>
 *   <li>buying a book never costs less than the plain book inside it sells for (bought, stripped, the bare book
 *       sold back);</li>
 *   <li>selling one never pays as much as the player's own buy price (the shop's {@link YourPrice} cap), nor as
 *       much as their own cheapest way to it — two books of the level below, combined on an anvil.</li>
 * </ul>
 */
public final class EnchantTradeRule implements IEconomyRule {

    private final EnchantCostRule cost = new EnchantCostRule();

    /**
     * @param plainBookSell what this player is paid for a plain book; zero when the shop takes none
     */
    public Optional<Money> buy(YourPrice yours, Money plainBookSell) {
        if (!yours.shop().buyable()) {
            return Optional.empty();
        }
        Money floor = plainBookSell.isPositive() ? plainBookSell.plus(Money.of(1)) : Money.ZERO;
        return yours.buyFor(1).map(price -> price.max(floor));
    }

    /**
     * @param lowerBuys this player's buy price for each lower level, I first; empty where the shop sells none
     * @param anvil     the anvil's price for each level, I first (level I's unused); as long as the levels
     */
    public Optional<Money> sell(YourPrice yours, List<Optional<Money>> lowerBuys, long[] anvil) {
        if (!yours.shop().sellable()) {
            return Optional.empty();
        }
        Optional<Money> mine = yours.sellFor(1);
        if (mine.isEmpty() || !mine.get().isPositive()) {
            return Optional.empty();
        }
        int levels = lowerBuys.size() + 1;
        long[] buy = new long[levels];
        boolean[] open = new boolean[levels];
        long[] steps = new long[levels];
        for (int i = 0; i < lowerBuys.size(); i++) {
            open[i] = lowerBuys.get(i).isPresent();
            buy[i] = lowerBuys.get(i).map(Money::minor).orElse(Long.MAX_VALUE);
        }
        for (int i = 0; i < levels && i < anvil.length; i++) {
            steps[i] = anvil[i];
        }
        long capped = cost.capSell(mine.get().minor(), buy, open, steps);
        return capped > 0 ? Optional.of(Money.of(capped)) : Optional.empty();
    }

    @Override
    public String describe() {
        return "one player's price for an enchanted book, with no way through the shop that makes money";
    }
}
