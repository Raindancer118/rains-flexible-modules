package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Bill;
import de.raindancer.modules.economy.model.PaymentRefusal;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.PaymentRule;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Asking somebody to pay you. They get Accept and Deny buttons; nothing moves until they accept, and a
 * bill nobody answers simply runs out. One open bill per pair, so nobody can be buried in buttons.
 */
public final class BillService implements IEconomyService {

    private final Plugin plugin;
    private final Server server;
    private final RainEconomy economy;
    private final Messages messages;
    private final Effects effects;
    private final ChatButtons buttons;
    private final LongSupplier clock;
    private final PaymentRule rule = new PaymentRule();
    private final Map<String, Bill> open = new ConcurrentHashMap<>();
    private volatile EconomySettings settings;

    private volatile SupplyService supply;

    /** The money supply's settings; the shipped ones, which change nothing, until wired. */
    public void supply(SupplyService service) {
        this.supply = service;
    }

    private de.raindancer.modules.economy.SupplySettings supplied() {
        return SupplyService.settingsOf(supply);
    }

    public BillService(Plugin plugin, Server server, RainEconomy economy, Messages messages, Effects effects,
                       ChatButtons buttons, LongSupplier clock, EconomySettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.economy = economy;
        this.messages = messages;
        this.effects = effects;
        this.buttons = buttons;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    private static String pair(UUID from, UUID to) {
        return from + ">" + to;
    }

    public void send(Player from, Player to, Money amount, String reason) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.billsEnabled()) {
            refuse(from, "economy.bill.off");
            return;
        }
        Optional<PaymentRefusal> refusal = rule.refusal(from.getUniqueId(), to.getUniqueId(), amount,
                live.payMinimumMoney());
        if (refusal.isPresent()) {
            refuse(from, refusal.get() == PaymentRefusal.TO_YOURSELF ? "economy.bill.yourself"
                    : "economy.not-an-amount");
            return;
        }
        long now = clock.getAsLong();
        open.values().removeIf(bill -> bill.expired(now));
        String key = pair(from.getUniqueId(), to.getUniqueId());
        if (open.containsKey(key)) {
            refuse(from, "economy.bill.already", "player", to.getName());
            return;
        }
        Duration life = Duration.ofMinutes(Math.max(1, live.billMinutes()));
        Bill bill = new Bill(UUID.randomUUID(), from.getUniqueId(), from.getName(), to.getUniqueId(), amount,
                reason == null ? "" : reason.strip(), now + life.toMillis());
        open.put(key, bill);

        messages.send(from, "economy.bill.sent", "amount", currency.render(amount), "player", to.getName(),
                "minutes", String.valueOf(life.toMinutes()));
        messages.send(to, bill.reason().isEmpty() ? "economy.bill.received" : "economy.bill.received-for",
                "amount", currency.render(amount), "player", from.getName(), "reason", bill.reason(),
                "buttons", buttons.ask(to.getUniqueId(), life,
                        clicker -> Scheduling.entity(plugin, to, () -> accept(key, bill)),
                        clicker -> decline(key, bill)));
        effects.play(to.getUniqueId(), Cues.NOTIFY);
    }

    private void accept(String key, Bill bill) {
        if (!open.remove(key, bill) || bill.expired(clock.getAsLong())) {
            Player payer = server.getPlayer(bill.to());
            if (payer != null) {
                refuse(payer, "economy.bill.gone");
            }
            return;
        }
        Currency currency = settings.currency();
        Player payer = server.getPlayer(bill.to());
        long wait = new de.raindancer.modules.economy.rules.VestingRule().hoursLeft(
                economy.book().find(bill.to()).map(de.raindancer.modules.economy.model.Account::created).orElse(0L),
                clock.getAsLong(), supplied().newAccountLock() ? supplied().newAccountHours() : 0);
        if (wait > 0) {
            if (payer != null) {
                refuse(payer, "economy.pay.too-new", "hours", String.valueOf(wait));
            }
            return;
        }
        EconomyResult result = economy.transfer(bill.to(), bill.from(), bill.amount(), Money.ZERO, TransactionKind.BILL,
                bill.reason());
        Player biller = server.getPlayer(bill.from());
        if (!result.succeeded()) {
            if (payer != null) {
                Outcomes.tell(messages, effects, payer, result, currency, bill.fromName());
            }
            if (biller != null) {
                messages.send(biller, "economy.bill.failed", "player", payer == null ? "" : payer.getName());
            }
            return;
        }
        if (payer != null) {
            messages.send(payer, "economy.bill.paid", "amount", currency.render(bill.amount()),
                    "player", bill.fromName());
            effects.play(payer.getUniqueId(), Cues.OK);
        }
        if (biller != null) {
            messages.send(biller, "economy.bill.was-paid", "amount", currency.render(bill.amount()),
                    "player", payer == null ? "" : payer.getName());
            effects.play(biller.getUniqueId(), Cues.EARNED);
        }
    }

    private void decline(String key, Bill bill) {
        open.remove(key, bill);
        Player biller = server.getPlayer(bill.from());
        Player payer = server.getPlayer(bill.to());
        if (biller != null) {
            messages.send(biller, "economy.bill.declined", "player", payer == null ? "" : payer.getName());
        }
        if (payer != null) {
            messages.send(payer, "economy.bill.you-declined", "player", bill.fromName());
        }
    }

    public void forget(UUID player) {
        open.values().removeIf(bill -> bill.from().equals(player) || bill.to().equals(player));
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
