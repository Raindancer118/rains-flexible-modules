package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.PaymentRefusal;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.PaymentRule;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** One player paying another: every check, the tax, the confirmation, and what both of them are told. */
public final class PaymentService implements IEconomyService {

    private final Plugin plugin;
    private final RainEconomy economy;
    private final Messages messages;
    private final Effects effects;
    private final ChatButtons buttons;
    private final PaymentRule rule = new PaymentRule();
    private final Cooldowns<UUID> between = new Cooldowns<>();
    private volatile EconomySettings settings;

    private volatile SupplyService supply;

    /** The money supply's settings; the shipped ones, which change nothing, until wired. */
    public void supply(SupplyService service) {
        this.supply = service;
    }

    private de.raindancer.modules.economy.SupplySettings supplied() {
        return SupplyService.settingsOf(supply);
    }

    public PaymentService(Plugin plugin, RainEconomy economy, Messages messages, Effects effects, ChatButtons buttons,
                          EconomySettings settings) {
        this.plugin = plugin;
        this.economy = economy;
        this.messages = messages;
        this.effects = effects;
        this.buttons = buttons;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
        between.every(Duration.ofSeconds(Math.max(0, this.settings.payCooldownSeconds())));
    }

    /**
     * Pays somebody, or asks for a confirming click first when the amount is large.
     *
     * @param confirmed whether the confirming click has already happened
     */
    public void pay(Player payer, OfflinePlayer target, Money amount, boolean confirmed) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.payEnabled()) {
            refuse(payer, "economy.pay.off");
            return;
        }
        Optional<PaymentRefusal> refusal = rule.refusal(payer.getUniqueId(), target.getUniqueId(), amount,
                live.payMinimumMoney());
        if (refusal.isPresent()) {
            switch (refusal.get()) {
                case TO_YOURSELF -> refuse(payer, "economy.pay.yourself");
                case NOT_POSITIVE -> refuse(payer, "economy.not-an-amount");
                case BELOW_MINIMUM -> refuse(payer, "economy.pay.too-little", "minimum",
                        currency.render(live.payMinimumMoney()));
            }
            return;
        }
        String name = PlayerTargets.shownName(target);
        if (!target.isOnline() && !live.payOffline()) {
            refuse(payer, "economy.pay.offline-off", "player", name);
            return;
        }
        if (!economy.hasAccount(target.getUniqueId())) {
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                refuse(payer, "economy.unknown-player", "player", name);
                return;
            }
            economy.open(target.getUniqueId(), target.getName());
        }
        long wait = new de.raindancer.modules.economy.rules.VestingRule().hoursLeft(
                economy.book().find(payer.getUniqueId()).map(de.raindancer.modules.economy.model.Account::created)
                        .orElse(0L), System.currentTimeMillis(), supplied().newAccountHours());
        if (wait > 0) {
            refuse(payer, "economy.pay.too-new", "hours", String.valueOf(wait));
            return;
        }
        if (!between.isReady(payer.getUniqueId())) {
            long seconds = between.remaining(payer.getUniqueId()).orElse(Duration.ZERO).toSeconds();
            refuse(payer, "economy.pay.wait", "seconds", String.valueOf(Math.max(1, seconds)));
            return;
        }
        if (!confirmed && rule.needsConfirming(amount, live.payConfirmAboveMoney())) {
            UUID payerId = payer.getUniqueId();
            messages.send(payer, "economy.pay.confirm", "amount", currency.render(amount), "player", name,
                    "button", buttons.label("<green>[Pay]</green>").tooltip("<gray>Send it")
                            .forOnly(payerId).expiringIn(Duration.ofSeconds(30))
                            .does(clicker -> Scheduling.entity(plugin, payer, () -> pay(payer, target, amount, true)))
                            .render());
            return;
        }

        Money tax = taxOn(amount, live);
        EconomyResult result = economy.transfer(payer.getUniqueId(), target.getUniqueId(), amount, tax,
                TransactionKind.PAY, "");
        if (!result.succeeded()) {
            Outcomes.tell(messages, effects, payer, result, currency, name);
            return;
        }
        between.start(payer.getUniqueId());
        effects.play(payer.getUniqueId(), Cues.OK);
        messages.send(payer, "economy.pay.sent", "amount", currency.render(amount), "player", name,
                "balance", currency.render(result.balance()));
        if (tax.isPositive()) {
            messages.send(payer, "economy.pay.taxed", "tax", currency.render(tax), "player", name);
        }
        Player online = target.getPlayer();
        if (online != null) {
            effects.play(online.getUniqueId(), Cues.EARNED);
            messages.send(online, "economy.pay.received", "amount", currency.render(amount.minus(tax)),
                    "player", payer.getName());
        }
    }

    /**
     * The tax on a payment: by slices when the owner wrote brackets, the flat rate otherwise, then turned by the
     * economy's levers; never more than the payment.
     */
    Money taxOn(Money amount, EconomySettings live) {
        java.util.List<String> written = supplied().payTaxBrackets();
        Money tax = written.isEmpty() ? rule.tax(amount, live.payTax())
                : new de.raindancer.modules.economy.rules.BracketRule().parse(written, live.currency())
                .map(brackets -> new de.raindancer.modules.economy.rules.BracketRule().tax(amount, brackets))
                .orElseGet(() -> rule.tax(amount, live.payTax()));
        return de.raindancer.core.social.economy.EconomyLevers.sink(de.raindancer.modules.economy.model.Sources.PAY_TAX, tax).min(amount);
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }

    public void forget(UUID player) {
        between.forget(player);
    }
}
