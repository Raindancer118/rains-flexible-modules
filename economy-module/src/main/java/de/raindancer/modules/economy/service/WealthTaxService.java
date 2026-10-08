package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Account;
import de.raindancer.modules.economy.model.TaxRun;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.WealthTaxRule;
import de.raindancer.modules.economy.store.AccountBook;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The wealth tax: a percentage of every player's bank balance, taken at an interval and destroyed — a money
 * sink for a server where too much has piled up. Off unless an owner switches it on; staff can also run it
 * once by hand.
 */
public final class WealthTaxService implements IEconomyService {

    private final Plugin plugin;
    private final Server server;
    private final RainEconomy economy;
    private final AccountBook book;
    private final Messages messages;
    private final LongSupplier clock;
    private final WealthTaxRule rule = new WealthTaxRule();
    private volatile EconomySettings settings;

    public WealthTaxService(Plugin plugin, Server server, RainEconomy economy, Messages messages, LongSupplier clock,
                            EconomySettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.economy = economy;
        this.book = economy.book();
        this.messages = messages;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    /** Asked once a minute. Switched off, the clock is let go, so switching it on later does not tax at once. */
    public void minute() {
        EconomySettings live = settings;
        if (!book.isLoaded()) {
            return;
        }
        long now = clock.getAsLong();
        long last = book.lastWealthTax();
        if (!live.wealthTaxEnabled()) {
            if (last != 0) {
                book.markWealthTax(0);
            }
            return;
        }
        if (last == 0) {
            book.markWealthTax(now);
            return;
        }
        if (rule.due(last, now, live.wealthTaxHours())) {
            run(live.wealthTaxPercent());
        }
    }

    /** What a run at this percentage would take now, without taking it. */
    public TaxRun preview(double percent) {
        Money allowance = settings.wealthTaxAllowanceMoney();
        int count = 0;
        Money total = Money.ZERO;
        for (Account account : book.all()) {
            if (AccountBook.isSystem(account.id())) {
                continue;
            }
            Money owed = rule.owed(account.balance(), percent, allowance);
            if (owed.isPositive()) {
                count++;
                total = total.plus(owed);
            }
        }
        return new TaxRun(count, total);
    }

    /** Takes the tax now, tells everybody, and tells each player online what they paid. */
    public TaxRun run(double percent) {
        Currency currency = settings.currency();
        Money allowance = settings.wealthTaxAllowanceMoney();
        Map<UUID, Money> before = new HashMap<>();
        server.getOnlinePlayers().forEach(player -> before.put(player.getUniqueId(), economy.balance(player.getUniqueId())));
        String share = String.format("%s", percent % 1 == 0 ? String.valueOf((long) percent) : String.valueOf(percent));
        TaxRun ran = book.wealthTax(balance -> rule.owed(balance, percent, allowance), "Wealth tax " + share + "%",
                clock.getAsLong());
        Scheduling.async(plugin, book::flush);
        for (Player player : server.getOnlinePlayers()) {
            messages.send(player, allowance.isPositive() ? "economy.tax.ran-allowance" : "economy.tax.ran",
                    "percent", share, "allowance", currency.render(allowance), "amount", currency.render(ran.total()),
                    "count", String.valueOf(ran.accounts()));
            Money was = before.get(player.getUniqueId());
            Money now = economy.balance(player.getUniqueId());
            if (was != null && was.isMoreThan(now)) {
                Money paid = was.minus(now);
                economy.tell(player.getUniqueId(), paid.negate(), now, TransactionKind.TAX);
                messages.send(player, "economy.tax.you-paid", "amount", currency.render(paid), "balance", currency.render(now));
            }
        }
        return ran;
    }

    /** {@code /eco tax <percent>}: a preview, and with {@code confirm} the real thing. */
    public void byHand(CommandSender staff, double percent, boolean confirmed) {
        Currency currency = settings.currency();
        String share = percent % 1 == 0 ? String.valueOf((long) percent) : String.valueOf(percent);
        if (!(percent > 0) || percent > 100) {
            messages.send(staff, "economy.tax.percent");
            return;
        }
        if (!confirmed) {
            TaxRun would = preview(percent);
            messages.send(staff, "economy.tax.preview", "percent", share, "amount", currency.render(would.total()),
                    "count", String.valueOf(would.accounts()));
            return;
        }
        TaxRun ran = run(percent);
        messages.send(staff, "economy.tax.done", "percent", share, "amount", currency.render(ran.total()),
                "count", String.valueOf(ran.accounts()));
    }
}
