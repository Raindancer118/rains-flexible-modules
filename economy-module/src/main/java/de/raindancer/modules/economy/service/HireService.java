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

    /** Either side ending a job. */
    public boolean end(Player who, Contract contract) {
        if (!contract.involves(who.getUniqueId()) || !book.endContract(contract.id())) {
            refuse(who, "economy.hire.not-yours");
            return false;
        }
        boolean employer = contract.employer().equals(who.getUniqueId());
        UUID other = employer ? contract.employee() : contract.employer();
        String otherName = employer ? contract.employeeName() : contract.employerName();
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
