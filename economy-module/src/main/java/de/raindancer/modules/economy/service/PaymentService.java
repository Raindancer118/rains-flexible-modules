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
        pay(new Payer() {
            @Override
            public UUID id() {
                return payer.getUniqueId();
            }

            @Override
            public String name() {
                return payer.getName();
            }

            @Override
            public void tell(String key, Object... values) {
                messages.send(payer, key, values);
            }

            @Override
            public void cue(String cue) {
                effects.play(payer.getUniqueId(), cue);
            }

            @Override
            public void askToConfirm(Money amount, String name, Runnable pay) {
                messages.send(payer, "economy.pay.confirm", "amount", settings.currency().render(amount), "player", name,
                        "button", buttons.label("<green>[Pay]</green>").tooltip("<gray>Send it")
                                .forOnly(payer.getUniqueId()).expiringIn(Duration.ofSeconds(30))
                                .does(clicker -> Scheduling.entity(plugin, payer, pay))
                                .render());
            }
        }, target, amount, confirmed);
    }

    /** Who pays: somebody here, or somebody on the linked server whose lines go back over the link. */
    public interface Payer {
        UUID id();

        String name();

        void tell(String key, Object... values);

        void cue(String cue);

        /** Asks for a confirming click; {@code pay} is the payment once they gave it. */
        void askToConfirm(Money amount, String name, Runnable pay);

        /** The money moved. */
        default void paid() {
        }
    }

    /**
     * Pays for somebody on the linked server (the arena), under exactly the rules a payment here has — on, minimum,
     * offline, the new-account lock, the wait between payments, confirming, the tax. Every line they would have been
     * told is returned, as JSON, for that server to show; {@code confirm} when a confirming click is needed first.
     */
    public java.util.Map<String, String> payAcross(UUID payerId, String payerName, OfflinePlayer target, Money amount,
                                                   boolean confirmed) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        boolean[] confirm = {false};
        boolean[] paid = {false};
        net.kyori.adventure.text.serializer.gson.GsonComponentSerializer json =
                net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();
        pay(new Payer() {
            @Override
            public UUID id() {
                return payerId;
            }

            @Override
            public String name() {
                return payerName;
            }

            @Override
            public void tell(String key, Object... values) {
                lines.add(json.serialize(messages.variant(key, values)));
            }

            @Override
            public void cue(String cue) {
                // The arena plays its own.
            }

            @Override
            public void askToConfirm(Money amount, String name, Runnable pay) {
                confirm[0] = true;
            }

            @Override
            public void paid() {
                paid[0] = true;
            }
        }, target, amount, confirmed);
        java.util.Map<String, String> answer = new java.util.HashMap<>();
        answer.put("lines", String.join("\n", lines));
        answer.put("confirm", String.valueOf(confirm[0]));
        answer.put("paid", String.valueOf(paid[0]));
        return answer;
    }

    private void pay(Payer payer, OfflinePlayer target, Money amount, boolean confirmed) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.payEnabled()) {
            refuse(payer, "economy.pay.off");
            return;
        }
        Optional<PaymentRefusal> refusal = rule.refusal(payer.id(), target.getUniqueId(), amount,
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
                economy.book().find(payer.id()).map(de.raindancer.modules.economy.model.Account::created)
                        .orElse(0L), System.currentTimeMillis(), supplied().newAccountLock() ? supplied().newAccountHours() : 0);
        if (wait > 0) {
            refuse(payer, "economy.pay.too-new", "hours", String.valueOf(wait));
            return;
        }
        if (!between.isReady(payer.id())) {
            long seconds = between.remaining(payer.id()).orElse(Duration.ZERO).toSeconds();
            refuse(payer, "economy.pay.wait", "seconds", String.valueOf(Math.max(1, seconds)));
            return;
        }
        if (!confirmed && rule.needsConfirming(amount, live.payConfirmAboveMoney())) {
            payer.askToConfirm(amount, name, () -> pay(payer, target, amount, true));
            return;
        }

        Money tax = taxOn(amount, live);
        EconomyResult result = economy.transfer(payer.id(), target.getUniqueId(), amount, tax,
                TransactionKind.PAY, "");
        if (!result.succeeded()) {
            payer.tell(Outcomes.keyOf(result), "amount", currency.render(result.amount()),
                    "balance", currency.render(result.balance()), "player", name);
            payer.cue(Cues.NO);
            return;
        }
        between.start(payer.id());
        payer.paid();
        payer.cue(Cues.OK);
        payer.tell("economy.pay.sent", "amount", currency.render(amount), "player", name,
                "balance", currency.render(result.balance()));
        if (tax.isPositive()) {
            payer.tell("economy.pay.taxed", "tax", currency.render(tax), "player", name);
        }
        Player online = target.getPlayer();
        if (online != null) {
            effects.play(online.getUniqueId(), Cues.EARNED);
            messages.send(online, "economy.pay.received", "amount", currency.render(amount.minus(tax)),
                    "player", payer.name());
        }
    }

    /**
     * The tax on a payment: by slices when the owner wrote brackets, the flat rate otherwise, then turned by the
     * economy's levers; never more than the payment.
     */
    Money taxOn(Money amount, EconomySettings live) {
        java.util.List<String> written = supplied().payTaxByBrackets() ? supplied().payTaxBrackets()
                : java.util.List.of();
        Money tax = written.isEmpty() ? rule.tax(amount, live.payTax())
                : new de.raindancer.modules.economy.rules.BracketRule().parse(written, live.currency())
                .map(brackets -> new de.raindancer.modules.economy.rules.BracketRule().tax(amount, brackets))
                .orElseGet(() -> rule.tax(amount, live.payTax()));
        return de.raindancer.core.social.economy.EconomyLevers.sink(de.raindancer.modules.economy.model.Sources.PAY_TAX, tax).min(amount);
    }

    private void refuse(Payer payer, String key, Object... values) {
        payer.tell(key, values);
        payer.cue(Cues.NO);
    }

    public void forget(UUID player) {
        between.forget(player);
    }
}
