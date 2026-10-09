package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.SupplySettings;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.DeathRule;

import java.util.Locale;
import java.util.UUID;

/** Dying costs a share of the balance, destroyed. Off unless the owner sets a share. */
public final class DeathService implements IEconomyService {

    public static final String SOURCE = de.raindancer.modules.economy.model.Sources.DEATH;

    private final RainEconomy economy;
    private final Messages messages;
    private final SupplyService supply;
    private final DeathRule rule = new DeathRule();
    private volatile EconomySettings settings;

    public DeathService(RainEconomy economy, Messages messages, SupplyService supply, EconomySettings settings) {
        this.economy = economy;
        this.messages = messages;
        this.supply = supply;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    /** What dying here costs this player now; zero when it costs nothing. */
    public Money loss(UUID player, String world) {
        SupplySettings live = SupplyService.settingsOf(supply);
        if (!live.deathCosts() || !(live.deathLosePercent() > 0)) {
            return Money.ZERO;
        }
        if (!live.deathWorlds().isEmpty() && live.deathWorlds().stream()
                .noneMatch(name -> name.strip().toLowerCase(Locale.ROOT).equals(world.toLowerCase(Locale.ROOT)))) {
            return Money.ZERO;
        }
        return rule.loss(economy.balance(player), live.deathLosePercent(),
                SupplySettings.money(live.deathLoseMost(), settings.currency()));
    }

    /** Takes it, and tells the player when somebody is listening. */
    public Money died(UUID player, String world, org.bukkit.entity.Player online) {
        Money loss = loss(player, world);
        if (!loss.isPositive()) {
            return Money.ZERO;
        }
        EconomyResult taken = economy.move(player, loss.negate(), TransactionKind.DEATH, "", SOURCE);
        if (!taken.succeeded()) {
            return Money.ZERO;
        }
        if (online != null) {
            Currency currency = settings.currency();
            messages.send(online, "economy.death.lost", "amount", currency.render(loss),
                    "balance", currency.render(taken.balance()));
        }
        return loss;
    }
}
