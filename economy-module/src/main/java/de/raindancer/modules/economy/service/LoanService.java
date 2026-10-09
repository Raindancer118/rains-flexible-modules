package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Loan;
import de.raindancer.modules.economy.model.LoanCollection;
import de.raindancer.modules.economy.model.LoanRefusal;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.LoanRule;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Loans from the bank: borrowed in one go with the interest added, paid back whenever the player likes, and
 * collected from the balance once due. The bank's money is made when lent and destroyed when paid back, so
 * interest and late fees are a money sink.
 */
public final class LoanService implements IEconomyService {

    private final Server server;
    private final RainEconomy economy;
    private final AccountBook book;
    private final Messages messages;
    private final Effects effects;
    private final LongSupplier clock;
    private final LoanRule rule = new LoanRule();
    private final de.raindancer.modules.economy.rules.CreditRule credit =
            new de.raindancer.modules.economy.rules.CreditRule();
    private volatile EconomySettings settings;

    public LoanService(Server server, RainEconomy economy, Messages messages, Effects effects, LongSupplier clock,
                       EconomySettings settings) {
        this.server = server;
        this.economy = economy;
        this.book = economy.book();
        this.messages = messages;
        this.effects = effects;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public Optional<Loan> loanOf(UUID player) {
        return book.loanOf(player);
    }

    /** How much this player may borrow, and why — the largest loan for everybody when the owner set no limit of their own. */
    public de.raindancer.modules.economy.rules.CreditRule.Limit limitOf(UUID player) {
        EconomySettings live = settings;
        de.raindancer.modules.economy.rules.CreditRule.Limit own = credit.limit(
                new de.raindancer.modules.economy.rules.CreditRule.Standing(economy.balance(player),
                        book.credit(player, live.loanRecentHours())),
                live.loanMostMoney());
        if (live.loanPersonalLimit()) {
            return own;
        }
        return new de.raindancer.modules.economy.rules.CreditRule.Limit(live.loanMostMoney(), own.capacity(),
                own.earned(), own.spent(), own.gambledAway(), own.spending(), own.gambling(), own.record(),
                own.lostLately(), own.lately());
    }

    /** What borrowing this much would cost to pay back. */
    public Money owedFor(Money amount) {
        return rule.owedFor(amount, settings.loanInterest());
    }

    /** Whether this player's loan is past its due date — no gambling then, if the owner says so. */
    public boolean overdue(UUID player) {
        long now = clock.getAsLong();
        return book.loanOf(player).map(loan -> loan.overdue(now)).orElse(false);
    }

    /** How long until a loan is due, in words; "now" once it is. */
    public String dueIn(Loan loan) {
        long left = loan.dueAt() - clock.getAsLong();
        return left <= 0 ? "now" : Times.describe(Duration.ofMillis(left));
    }

    public void borrow(Player player, Money amount) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.loansEnabled()) {
            refuse(player, "economy.loan.off");
            return;
        }
        if (!player.hasPermission(PermissionNodes.LOAN)) {
            refuse(player, "economy.not-allowed");
            return;
        }
        Money own = limitOf(player.getUniqueId()).amount();
        Optional<LoanRefusal> refusal = rule.refusal(amount, live.loanLeastMoney(), live.loanMostMoney(),
                live.loanPersonalLimit() ? Optional.of(own) : Optional.empty(),
                book.loanOf(player.getUniqueId()).isPresent());
        if (refusal.isPresent()) {
            switch (refusal.get()) {
                case HAS_LOAN -> refuse(player, "economy.loan.has-loan");
                case TOO_LITTLE -> refuse(player, "economy.loan.too-little", "amount", currency.render(live.loanLeastMoney()));
                case TOO_MUCH -> refuse(player, "economy.loan.too-much", "amount", currency.render(live.loanMostMoney()));
                case OVER_OWN_LIMIT -> refuse(player, "economy.loan.over-own-limit", "amount", currency.render(own));
            }
            return;
        }
        economy.open(player.getUniqueId(), player.getName());
        long now = clock.getAsLong();
        Loan loan = new Loan(player.getUniqueId(), player.getName(), amount, owedFor(amount), now,
                now + live.loanDays() * LoanRule.DAY, now);
        EconomyResult paid = book.borrow(loan, economy.most());
        if (!paid.succeeded()) {
            Outcomes.tell(messages, effects, player, paid, currency, "");
            return;
        }
        economy.tell(player.getUniqueId(), amount, paid.balance(), TransactionKind.LOAN);
        messages.send(player, "economy.loan.taken", "amount", currency.render(amount),
                "owed", currency.render(loan.owed()), "days", String.valueOf(live.loanDays()));
        effects.play(player.getUniqueId(), Cues.OK);
    }

    /** @param amount how much to pay back; empty for all of it, or as much as the balance holds */
    public void repay(Player player, Optional<Money> amount) {
        Currency currency = settings.currency();
        Optional<Loan> loan = book.loanOf(player.getUniqueId());
        if (loan.isEmpty()) {
            refuse(player, "economy.loan.nothing-owed");
            return;
        }
        Money balance = economy.balance(player.getUniqueId());
        Money paying = amount.orElse(loan.get().owed()).min(loan.get().owed()).min(balance);
        if (!paying.isPositive()) {
            refuse(player, "economy.loan.cannot-pay", "owed", currency.render(loan.get().owed()));
            return;
        }
        EconomyResult paid = book.repay(player.getUniqueId(), paying);
        if (!paid.succeeded()) {
            Outcomes.tell(messages, effects, player, paid, currency, "");
            return;
        }
        economy.tell(player.getUniqueId(), paying.negate(), paid.balance(), TransactionKind.LOAN);
        Optional<Loan> left = book.loanOf(player.getUniqueId());
        if (left.isEmpty()) {
            messages.send(player, "economy.loan.cleared", "amount", currency.render(paying));
        } else {
            messages.send(player, "economy.loan.repaid", "amount", currency.render(paying),
                    "owed", currency.render(left.get().owed()));
        }
        effects.play(player.getUniqueId(), Cues.OK);
    }

    /** Staff wiping a loan. */
    public Optional<Loan> forgive(UUID player) {
        Optional<Loan> loan = book.loanOf(player);
        if (loan.isPresent() && book.forgive(player)) {
            Player online = server.getPlayer(player);
            if (online != null) {
                messages.send(online, "economy.loan.forgiven-you", "owed", settings.currency().render(loan.get().owed()));
            }
            return loan;
        }
        return Optional.empty();
    }

    /** On join: a word about what is owed, so nobody is surprised by the bank collecting. */
    public void joined(Player player) {
        book.loanOf(player.getUniqueId()).ifPresent(loan -> {
            Currency currency = settings.currency();
            if (loan.overdue(clock.getAsLong())) {
                messages.send(player, "economy.loan.reminder-overdue", "owed", currency.render(loan.owed()));
            } else {
                messages.send(player, "economy.loan.reminder", "owed", currency.render(loan.owed()), "when", dueIn(loan));
            }
        });
    }

    /** Asked once a minute: late fees and collection on every overdue loan. Runs even with loans switched off. */
    public void minute() {
        if (!book.isLoaded()) {
            return;
        }
        Currency currency = settings.currency();
        for (LoanCollection done : book.collectLoans(clock.getAsLong(), settings.loanLate(), economy.most())) {
            UUID id = done.loan().player();
            if (done.taken().isPositive()) {
                economy.tell(id, done.taken().negate(), economy.balance(id), TransactionKind.LOAN);
            }
            Player player = server.getPlayer(id);
            if (player == null) {
                continue;
            }
            if (done.feeAdded().isPositive()) {
                messages.send(player, "economy.loan.late-fee", "fee", currency.render(done.feeAdded()),
                        "owed", currency.render(done.loan().owed().plus(done.taken())));
            }
            if (done.cleared()) {
                messages.send(player, "economy.loan.collected-cleared", "amount", currency.render(done.taken()));
            } else if (done.taken().isPositive()) {
                messages.send(player, "economy.loan.collected", "amount", currency.render(done.taken()),
                        "owed", currency.render(done.loan().owed()));
            }
        }
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
