package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.entity.Player;

/** The sentence for every way moving money can be refused, said the same way everywhere. */
public final class Outcomes {

    private Outcomes() {
    }

    /** @param other who was on the other side, for "they cannot hold that much" — may be empty */
    public static void tell(Messages messages, Effects effects, Player player, EconomyResult result,
                            Currency currency, String other) {
        String key = switch (result.outcome()) {
            case NOT_ENOUGH -> "economy.not-enough";
            case TOO_MUCH -> "economy.too-much";
            case NO_ACCOUNT -> "economy.no-account";
            case FROZEN -> "economy.frozen";
            case INVALID_AMOUNT -> "economy.not-an-amount";
            case UNAVAILABLE -> "economy.unavailable";
            case REFUSED, DONE -> "economy.refused";
        };
        messages.send(player, key, "amount", currency.render(result.amount()),
                "balance", currency.render(result.balance()), "player", other == null ? "" : other);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
