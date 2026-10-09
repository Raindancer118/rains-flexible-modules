package de.raindancer.modules.economy.util;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.PersonalPrice;

/**
 * A price as one player sees it: the shop's crossed out next to theirs, and why it differs, so a role's
 * discount is visible where it is spent rather than only promised in the role's description.
 */
public final class PriceLines {

    private PriceLines() {
    }

    /** @param everybody what the line costs anybody; @param theirs what it costs this player */
    public static String amount(Currency currency, Money everybody, Money theirs, PersonalPrice why) {
        String mine = Mini.of(currency.render(theirs));
        if (everybody.equals(theirs)) {
            return mine;
        }
        String reason = why.reasons().isEmpty() ? "" : " <dark_aqua>" + String.join(", ", why.reasons());
        String percent = why.percent() == 0 ? ""
                : " <dark_gray>(" + (why.percent() > 0 ? "+" : "−") + Math.abs(why.percent()) + "%)";
        return "<dark_gray><st>" + Mini.of(currency.render(everybody)) + "</st> " + mine + reason + percent;
    }
}
