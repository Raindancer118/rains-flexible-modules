package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Contract;
import de.raindancer.modules.economy.model.Payday;
import de.raindancer.modules.economy.store.AccountBook;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * {@code /hire}: a wage paid from the employer's account to the employee's every so often, for as long as
 * either wants. Nobody is hired without saying yes; either side can end it; a wage the employer cannot pay
 * is missed, and too many missed in a row end the job.
 */
public final class HireService implements IEconomyService {

    private final Plugin plugin;
    private final Server server;
    private final RainEconomy economy;
    private final AccountBook book;
    private final Messages messages;
    private final Effects effects;
    private final ChatButtons buttons;
    private final LongSupplier clock;
    private volatile EconomySettings settings;

    public HireService(Plugin plugin, Server server, RainEconomy economy, Messages messages, Effects effects,
                       ChatButtons buttons, LongSupplier clock, EconomySettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.economy = economy;
        this.book = economy.book();
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

    public List<Contract> contractsOf(UUID player) {
        return book.contractsOf(player);
    }

    /** Offers somebody a job; nothing starts until they accept. */
    public void offer(Player employer, Player employee, Money wage, Duration every, String title) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.hireEnabled()) {
            refuse(employer, "economy.hire.off");
            return;
        }
        if (employer.getUniqueId().equals(employee.getUniqueId())) {
            refuse(employer, "economy.hire.yourself");
            return;
        }
        if (!wage.isPositive()) {
            refuse(employer, "economy.not-an-amount");
            return;
        }
        long minutes = every.toMinutes();
        if (minutes < live.hireLeastMinutes()) {
            refuse(employer, "economy.hire.too-often", "minutes", String.valueOf(live.hireLeastMinutes()));
            return;
        }
        if (book.employing(employer.getUniqueId()).size() >= live.hireMostContracts()) {
            refuse(employer, "economy.hire.too-many", "most", String.valueOf(live.hireMostContracts()));
            return;
        }
        String job = title == null ? "" : title.strip();
        String when = Times.describe(Duration.ofMinutes(minutes));
        messages.send(employer, "economy.hire.offered", "player", employee.getName(),
                "amount", currency.render(wage), "every", when);
        messages.send(employee, job.isEmpty() ? "economy.hire.offer" : "economy.hire.offer-as",
                "player", employer.getName(), "amount", currency.render(wage), "every", when, "job", job,
                "buttons", buttons.ask(employee.getUniqueId(), Duration.ofMinutes(5),
                        clicker -> Scheduling.entity(plugin, employee, () ->
                                accept(employer.getUniqueId(), employer.getName(), employee, wage, (int) minutes, job)),
                        clicker -> {
                            Player boss = server.getPlayer(employer.getUniqueId());
                            if (boss != null) {
                                messages.send(boss, "economy.hire.declined", "player", employee.getName());
                            }
                        }));
        effects.play(employee.getUniqueId(), Cues.NOTIFY);
    }

    /**
     * A contract of service — rent, upkeep, anything paid for regularly. Either side may propose it: the one
     * paying ({@code proposerPays}) or the one being paid, as a landlord does. Nothing starts until the other
     * side accepts, so nobody is ever charged without saying yes.
     */
    public void propose(Player proposer, Player other, boolean proposerPays, Money amount, Duration every, String what) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.hireEnabled()) {
            refuse(proposer, "economy.contract.off");
            return;
        }
        if (proposer.getUniqueId().equals(other.getUniqueId())) {
            refuse(proposer, "economy.contract.yourself");
            return;
        }
        if (!amount.isPositive()) {
            refuse(proposer, "economy.not-an-amount");
            return;
        }
        String service = what == null ? "" : what.strip();
        if (service.isEmpty()) {
            refuse(proposer, "economy.usage.contract");
            return;
        }
        long minutes = every.toMinutes();
        if (minutes < live.hireLeastMinutes()) {
            refuse(proposer, "economy.hire.too-often", "minutes", String.valueOf(live.hireLeastMinutes()));
            return;
        }
        Player payer = proposerPays ? proposer : other;
        if (book.employing(payer.getUniqueId()).size() >= live.hireMostContracts()) {
            refuse(proposer, "economy.contract.too-many", "player", payer.getName(),
                    "most", String.valueOf(live.hireMostContracts()));
            return;
        }
        String when = Times.describe(Duration.ofMinutes(minutes));
        messages.send(proposer, proposerPays ? "economy.contract.offered-pay" : "economy.contract.offered-charge",
                "player", other.getName(), "amount", currency.render(amount), "every", when, "what", service);
        messages.send(other, proposerPays ? "economy.contract.offer-pay" : "economy.contract.offer-charge",
                "player", proposer.getName(), "amount", currency.render(amount), "every", when, "what", service,
                "buttons", buttons.ask(other.getUniqueId(), Duration.ofMinutes(5),
                        clicker -> Scheduling.entity(plugin, other, () ->
                                agree(proposer.getUniqueId(), proposer.getName(), other, proposerPays, amount,
                                        (int) minutes, service)),
                        clicker -> {
                            Player asking = server.getPlayer(proposer.getUniqueId());
                            if (asking != null) {
                                messages.send(asking, "economy.contract.declined", "player", other.getName());
                            }
                        }));
        effects.play(other.getUniqueId(), Cues.NOTIFY);
    }

    private void agree(UUID proposer, String proposerName, Player accepting, boolean proposerPays, Money amount,
                       int minutes, String what) {
        if (!settings.hireEnabled()) {
            refuse(accepting, "economy.contract.off");
            return;
        }
        UUID payer = proposerPays ? proposer : accepting.getUniqueId();
        String payerName = proposerPays ? proposerName : accepting.getName();
        UUID payee = proposerPays ? accepting.getUniqueId() : proposer;
        String payeeName = proposerPays ? accepting.getName() : proposerName;
        if (book.employing(payer).size() >= settings.hireMostContracts()) {
            refuse(accepting, "economy.contract.too-many", "player", payerName,
                    "most", String.valueOf(settings.hireMostContracts()));
            return;
        }
        economy.open(accepting.getUniqueId(), accepting.getName());
        long now = clock.getAsLong();
        book.hire(new Contract(UUID.randomUUID(), payer, payerName, payee, payeeName, amount, minutes,
                now + minutes * 60_000L, 0, what, now, Contract.Kind.SERVICE));
        Currency currency = settings.currency();
        String when = Times.describe(Duration.ofMinutes(minutes));
        tellIfOnline(payer, "economy.contract.started-payer", "player", payeeName, "amount", currency.render(amount),
                "every", when, "what", what);
        tellIfOnline(payee, "economy.contract.started-payee", "player", payerName, "amount", currency.render(amount),
                "every", when, "what", what);
    }

    private void tellIfOnline(UUID who, String key, Object... values) {
        Player online = server.getPlayer(who);
        if (online != null) {
            messages.send(online, key, values);
            effects.play(who, Cues.OK);
        }
    }

    private void accept(UUID employer, String employerName, Player employee, Money wage, int minutes, String job) {
        if (!settings.hireEnabled()) {
            refuse(employee, "economy.hire.off");
            return;
        }
        if (book.employing(employer).size() >= settings.hireMostContracts()) {
            refuse(employee, "economy.hire.full", "player", employerName);
            return;
        }
        economy.open(employee.getUniqueId(), employee.getName());
        long now = clock.getAsLong();
        Contract contract = new Contract(UUID.randomUUID(), employer, employerName, employee.getUniqueId(),
                employee.getName(), wage, minutes, now + minutes * 60_000L, 0, job, now);
        book.hire(contract);
        Currency currency = settings.currency();
        String when = Times.describe(Duration.ofMinutes(minutes));
        messages.send(employee, "economy.hire.started", "player", employerName, "amount", currency.render(wage),
                "every", when);
        effects.play(employee.getUniqueId(), Cues.OK);
        Player boss = server.getPlayer(employer);
        if (boss != null) {
            messages.send(boss, "economy.hire.accepted", "player", employee.getName(), "amount", currency.render(wage),
                    "every", when);
            effects.play(employer, Cues.OK);
        }
    }

    private void serviceDay(Payday day, Contract deal, Currency currency) {
        net.kyori.adventure.text.Component amount = currency.render(deal.wage());
        switch (day.kind()) {
            case PAID -> {
                economy.tell(deal.employee(), deal.wage(), economy.balance(deal.employee()),
                        de.raindancer.modules.economy.model.TransactionKind.CONTRACT);
                economy.tell(deal.employer(), deal.wage().negate(), economy.balance(deal.employer()),
                        de.raindancer.modules.economy.model.TransactionKind.CONTRACT);
                Player payee = server.getPlayer(deal.employee());
                if (payee != null) {
                    messages.send(payee, "economy.contract.paid", "player", deal.employerName(), "amount", amount,
                            "what", deal.title());
                }
            }
            case MISSED -> {
                tellIfOnline(deal.employee(), "economy.contract.missed", "player", deal.employerName(),
                        "amount", amount, "what", deal.title(), "count", String.valueOf(deal.missed()));
                tellIfOnline(deal.employer(), "economy.contract.could-not-pay", "player", deal.employeeName(),
                        "amount", amount, "what", deal.title());
            }
            case ENDED -> {
                tellIfOnline(deal.employee(), "economy.contract.ended", "player", deal.employerName(),
                        "what", deal.title());
                tellIfOnline(deal.employer(), "economy.contract.ended", "player", deal.employeeName(),
                        "what", deal.title());
            }
        }
    }

    /** Either side ending a job. */
    public boolean end(Player who, Contract contract) {
        if (!contract.involves(who.getUniqueId()) || !book.endContract(contract.id())) {
            refuse(who, "economy.hire.not-yours");
            return false;
        }
        boolean employer = contract.employer().equals(who.getUniqueId());
        UUID other = employer ? contract.employee() : contract.employer();
        String otherName = employer ? contract.employeeName() : contract.employerName();
        if (contract.isService()) {
            messages.send(who, "economy.contract.you-ended", "player", otherName, "what", contract.title());
            tellIfOnline(other, "economy.contract.ended-by", "player", who.getName(), "what", contract.title());
            return true;
        }
        messages.send(who, employer ? "economy.hire.you-fired" : "economy.hire.you-quit", "player", otherName);
        Player them = server.getPlayer(other);
        if (them != null) {
            messages.send(them, employer ? "economy.hire.fired" : "economy.hire.quit", "player", who.getName());
        }
        return true;
    }

    /** Asked once a minute: every wage that is due. */
    public void minute() {
        if (!settings.hireEnabled() || !book.isLoaded()) {
            return;
        }
        Currency currency = settings.currency();
        for (Payday day : book.payroll(clock.getAsLong(), settings.hireMostMissed(), economy.most())) {
            Contract job = day.contract();
            Player employee = server.getPlayer(job.employee());
            Player employer = server.getPlayer(job.employer());
            if (job.isService()) {
                serviceDay(day, job, currency);
                continue;
            }
            switch (day.kind()) {
                case PAID -> {
                    economy.tell(job.employee(), job.wage(), economy.balance(job.employee()),
                            de.raindancer.modules.economy.model.TransactionKind.WAGE);
                    economy.tell(job.employer(), job.wage().negate(), economy.balance(job.employer()),
                            de.raindancer.modules.economy.model.TransactionKind.WAGE);
                    if (employee != null) {
                        messages.send(employee, "economy.hire.paid", "player", job.employerName(),
                                "amount", currency.render(job.wage()));
                    }
                }
                case MISSED -> {
                    if (employee != null) {
                        messages.send(employee, "economy.hire.missed", "player", job.employerName(),
                                "count", String.valueOf(job.missed()));
                    }
                    if (employer != null) {
                        messages.send(employer, "economy.hire.could-not-pay", "player", job.employeeName(),
                                "amount", currency.render(job.wage()));
                    }
                }
                case ENDED -> {
                    if (employee != null) {
                        messages.send(employee, "economy.hire.ended", "player", job.employerName());
                    }
                    if (employer != null) {
                        messages.send(employer, "economy.hire.ended-employer", "player", job.employeeName());
                    }
                }
            }
        }
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
