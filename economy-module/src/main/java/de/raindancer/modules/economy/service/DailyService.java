package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Account;
import de.raindancer.modules.economy.model.DailyClaim;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.DailyRule;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/** /daily: once a day, more for every day in a row. */
public final class DailyService implements IEconomyService {

    private final RainEconomy economy;
    private final Messages messages;
    private final Effects effects;
    private final Clock clock;
    private final DailyRule rule = new DailyRule();
    private volatile EconomySettings settings;

    public DailyService(RainEconomy economy, Messages messages, Effects effects, Clock clock, EconomySettings settings) {
        this.economy = economy;
        this.messages = messages;
        this.effects = effects;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public long today() {
        return LocalDate.now(clock).toEpochDay();
    }

    /** Whether this player could claim right now, for a screen to grey the button. */
    public boolean ready(Player player) {
        Account account = economy.book().find(player.getUniqueId()).orElse(null);
        return settings.dailyEnabled() && (account == null || account.dailyDay() < today());
    }

    /** Hours until midnight, for "come back in". */
    public long hoursLeft() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime midnight = now.toLocalDate().plusDays(1).atTime(LocalTime.MIDNIGHT).atZone(now.getZone());
        return Math.max(1, java.time.Duration.between(now, midnight).toHours() + 1);
    }

    public void claim(Player player) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.dailyEnabled()) {
            refuse(player, "economy.daily.off");
            return;
        }
        Account account = economy.open(player.getUniqueId(), player.getName());
        DailyClaim claim = rule.claim(account.dailyDay(), account.dailyStreak(), today(), live.dailyStreakMost());
        if (!claim.allowed()) {
            refuse(player, "economy.daily.already", "hours", String.valueOf(hoursLeft()));
            return;
        }
        Money amount = de.raindancer.core.social.economy.EconomyLevers.faucet(de.raindancer.modules.economy.model.Sources.DAILY,
                rule.amount(live.dailyMoney(), live.dailyBonusMoney(), claim.streak()));
        if (!amount.isPositive()) {
            Outcomes.tell(messages, effects, player, EconomyResult.failed(EconomyResult.Outcome.TREASURY_EMPTY,
                    amount, economy.balance(player.getUniqueId())), currency, "");
            return;
        }
        EconomyResult result = economy.book().claimDaily(player.getUniqueId(), claim, amount, economy.most());
        if (!result.succeeded()) {
            if (result.outcome() == EconomyResult.Outcome.REFUSED) {
                refuse(player, "economy.daily.already", "hours", String.valueOf(hoursLeft()));
            } else {
                Outcomes.tell(messages, effects, player, result, currency, "");
            }
            return;
        }
        economy.tell(player.getUniqueId(), amount, result.balance(), TransactionKind.DAILY);
        economy.collectDebt(player.getUniqueId(), amount);
        effects.play(player.getUniqueId(), Cues.REWARD);
        messages.send(player, "economy.daily.claimed", "amount", currency.render(amount),
                "streak", String.valueOf(claim.streak()));
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
