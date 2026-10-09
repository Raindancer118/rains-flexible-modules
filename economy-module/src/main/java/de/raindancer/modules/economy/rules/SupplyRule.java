package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.MoneySupply;

/** How much payouts shrink because the treasury runs low, or because everybody is already rich. */
public final class SupplyRule implements IEconomyRule {

    /**
     * @param scaleBelowPercent below this share of the cap, payouts shrink in proportion; 0 for never
     * @param targetPerPlayer   above this much per active player, payouts shrink in proportion; zero for never
     * @return the change in percent, {@code -100..0}
     */
    public int faucetChange(MoneySupply supply, int scaleBelowPercent, Money targetPerPlayer) {
        double factor = 1.0;
        if (supply.capped() && scaleBelowPercent > 0) {
            double threshold = Math.min(100, scaleBelowPercent) / 100.0;
            if (supply.fill() < threshold) {
                factor *= supply.fill() / threshold;
            }
        }
        // Nobody counted yet (just started) says nothing about how rich players are.
        if (targetPerPlayer != null && targetPerPlayer.isPositive() && supply.activePlayers() > 0) {
            Money per = supply.perActivePlayer();
            if (per.isMoreThan(targetPerPlayer)) {
                factor *= (double) targetPerPlayer.minor() / per.minor();
            }
        }
        return (int) -Math.round((1.0 - factor) * 100);
    }

    @Override
    public String describe() {
        return "how much payouts shrink for a low treasury or a rich server";
    }
}
