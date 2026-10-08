package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.EconomyResult;

/**
 * What buying raffle tickets came to.
 *
 * @param bought  how many were bought — fewer than asked where a limit was reached
 * @param payment the money side, for why a {@link Kind#REFUSED} purchase could not be paid
 */
public record RaffleBuy(Kind kind, Raffle raffle, int bought, EconomyResult payment) {

    public enum Kind { BOUGHT, GONE, OWN, LIMIT, SOLD_OUT, REFUSED }
}
