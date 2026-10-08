package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.InterestRule;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.function.LongSupplier;

/** Interest on what is in the bank, paid to whoever is online when it falls due. */
public final class InterestService implements IEconomyService {

    private final RainEconomy economy;
    private final Messages messages;
    private final LongSupplier clock;
    private final InterestRule rule = new InterestRule();
    private volatile EconomySettings settings;
    private volatile long lastPaid;

    public InterestService(RainEconomy economy, Messages messages, LongSupplier clock, EconomySettings settings) {
        this.economy = economy;
        this.messages = messages;
        this.clock = clock;
        this.lastPaid = clock.getAsLong();
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    /** Asked once a minute; pays when the period has passed. */
    public int minute(Collection<? extends Player> online) {
        EconomySettings live = settings;
        long now = clock.getAsLong();
        if (!live.interestEnabled() || now - lastPaid < live.interestMinutes() * 60_000L) {
            return 0;
        }
        lastPaid = now;
        int paid = 0;
        for (Player player : online) {
            Money interest = rule.interest(economy.balance(player.getUniqueId()), live.interestRate(),
                    live.interestCapMoney(), live.most());
            if (!interest.isPositive()) {
                continue;
            }
            EconomyResult result = economy.move(player.getUniqueId(), interest, TransactionKind.INTEREST, "");
            if (result.succeeded()) {
                paid++;
                messages.send(player, "economy.earn.interest", "amount", live.currency().render(interest));
            }
        }
        return paid;
    }
}
