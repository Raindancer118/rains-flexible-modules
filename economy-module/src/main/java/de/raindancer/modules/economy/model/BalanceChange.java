package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;

/** What applying a change to a balance would give: the outcome, and the balance after if it happened. */
public record BalanceChange(EconomyResult.Outcome outcome, Money after) {

    public boolean allowed() {
        return outcome == EconomyResult.Outcome.DONE;
    }
}
