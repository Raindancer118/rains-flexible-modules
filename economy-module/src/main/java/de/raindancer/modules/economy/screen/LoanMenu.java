package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Loan;
import de.raindancer.modules.economy.rules.CreditRule;
import de.raindancer.modules.economy.rules.LoanRule;
import de.raindancer.modules.economy.rules.StakeRule;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/** Borrowing from the bank, and paying it back. */
public final class LoanMenu extends Menu implements IEconomyScreen {

    private static final StakeRule ROUNDING = new StakeRule();

    private final EconomyServices services;

    public LoanMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Loan");
    }

    @Override
    public String breadcrumb() {
        return "Loan";
    }

    @Override
    protected void render() {
        EconomySettings live = services.config();
        Currency currency = services.currency();
        Money balance = services.economy().balance(viewer.getUniqueId());
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance", Mini.of(currency.render(balance))));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.BOOK, "<white>How loans work",
                "<gray>" + CasinoMenu.percent(live.loanInterest()) + "% interest, added once.",
                "<gray>Due " + live.loanDays() + " day(s) after borrowing.",
                "<gray>Pay back early whenever you like.",
                "<gray>Once due, the bank takes what your",
                "<gray>balance holds until it is paid,",
                live.loanLate() > 0 ? "<gray>and adds " + CasinoMenu.percent(live.loanLate()) + "% for every day late." : "<gray>with no late fee.",
                live.overdueStopsGambling() ? "<dark_gray>No gambling while overdue." : ""));

        Optional<Loan> loan = services.loans().loanOf(viewer.getUniqueId());
        if (loan.isPresent()) {
            owing(loan.get(), currency, balance);
        } else {
            offers(live, currency);
        }
    }

    private void owing(Loan loan, Currency currency, Money balance) {
        boolean overdue = services.loans().overdue(viewer.getUniqueId());
        set(MenuLayout.HEADER_SUBJECT, Icons.of(overdue ? Material.REDSTONE_BLOCK : Material.GOLD_BLOCK,
                overdue ? "<red>Your loan is overdue" : "<gold>Your loan",
                "<gray>You owe " + Mini.of(currency.render(loan.owed())),
                "<gray>Borrowed " + Mini.of(currency.render(loan.borrowed())),
                overdue ? "<red>The bank is collecting it from your balance."
                        : "<gray>Due in " + services.loans().dueIn(loan)));
        Money all = loan.owed().min(balance);
        boolean whole = balance.isAtLeast(loan.owed());
        band(MenuLayout.RULES, 2, Icons.of(Material.LIME_CONCRETE,
                whole ? "<green>Pay it all back" : "<yellow>Pay back what you have",
                "<gray>" + Mini.of(currency.render(all)) + (whole ? "<gray>, and it is done." : "<gray> of it."),
                "<yellow>Click<gray> to pay"), click -> {
            services.loans().repay(viewer, Optional.empty());
            refresh();
        });
        Money half = ROUNDING.nice(loan.owed().share(0.5));
        band(MenuLayout.RULES, 4, Icons.of(Material.GOLD_INGOT, "<yellow>Pay back " + Mini.of(currency.render(half)),
                "<gray>About half.", "<yellow>Click<gray> to pay"), click -> {
            services.loans().repay(viewer, Optional.of(half));
            refresh();
        });
        band(MenuLayout.RULES, 6, Icons.of(Material.PAPER, "<white>Pay back an amount",
                "<yellow>Click<gray> to type one"), click -> MoneyPrompt.ask(viewer, "Pay back how much?", currency,
                amount -> {
                    services.loans().repay(viewer, Optional.of(amount));
                    open();
                }, this::open));
    }

    private void offers(EconomySettings live, Currency currency) {
        CreditRule.Limit limit = services.loans().limitOf(viewer.getUniqueId());
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.EMERALD, "<green>You owe the bank nothing",
                "<gray>Borrow " + Mini.of(currency.render(live.loanLeastMoney())) + "<gray> to "
                        + Mini.of(currency.render(limit.amount())) + "<gray>."));
        if (!live.loansEnabled()) {
            band(MenuLayout.RULES, 4, Icons.of(Material.BARRIER, "<red>Loans are switched off"));
            return;
        }
        if (live.loanPersonalLimit()) {
            band(MenuLayout.LAND, 2, Icons.of(Material.WRITABLE_BOOK, "<white>Your limit: " + Mini.of(currency.render(limit.amount())),
                    "<gray>What you have, plus a quarter of the",
                    "<gray>" + Mini.of(currency.render(limit.earned())) + "<gray> you ever earned: "
                            + Mini.of(currency.render(limit.capacity())),
                    "<gray>Spending " + Mini.of(currency.render(limit.spent())) + "<gray>: ×" + factor(limit.spending()),
                    "<gray>Gambled away " + Mini.of(currency.render(limit.gambledAway())) + "<gray>: ×" + factor(limit.gambling()),
                    "<gray>Lost lately (" + live.loanRecentHours() + " h played) "
                            + Mini.of(currency.render(limit.lostLately())) + "<gray>: ×" + factor(limit.lately()),
                    "<gray>Earlier loans: ×" + factor(limit.record()),
                    "<dark_gray>Never more than " + Mini.of(currency.render(live.loanMostMoney()))));
        }
        if (!limit.amount().isAtLeast(live.loanLeastMoney())) {
            band(MenuLayout.RULES, 4, Icons.of(Material.BARRIER, "<red>The bank will not lend to you yet",
                    "<gray>Your limit is below the smallest loan.", "<gray>Earn some money first."));
            return;
        }
        List<Money> amounts = new LoanRule().offers(live.loanLeastMoney(), limit.amount());
        int column = 4 - amounts.size() / 2;
        for (Money amount : amounts) {
            Money owed = services.loans().owedFor(amount);
            band(MenuLayout.RULES, column++, Icons.of(Material.GOLD_NUGGET,
                    "<yellow>Borrow " + Mini.of(currency.render(amount)),
                    "<gray>Pay back " + Mini.of(currency.render(owed)),
                    "<gray>within " + live.loanDays() + " day(s).", "<yellow>Click<gray> to borrow"),
                    click -> confirm(amount));
        }
        band(MenuLayout.LAND, 4, Icons.of(Material.PAPER, "<white>Borrow another amount",
                "<yellow>Click<gray> to type one"), click -> MoneyPrompt.ask(viewer, "Borrow how much?", currency,
                this::confirm, this::open));
    }

    private void confirm(Money amount) {
        EconomySettings live = services.config();
        Currency currency = services.currency();
        Money owed = services.loans().owedFor(amount);
        new ConfirmScreen(viewer, services.brand(), this, "<yellow>Borrow " + Mini.of(currency.render(amount)) + "<yellow>?",
                List.of("<gray>You will owe " + Mini.of(currency.render(owed)) + "<gray>,",
                        "<gray>due in " + live.loanDays() + " day(s).",
                        "<gray>After that the bank takes it from your balance."), () -> {
            services.loans().borrow(viewer, amount);
            open();
        }).open();
    }

    private static String factor(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Borrow from the bank, pay it back whenever you like.",
                "Once it is due, the bank collects it from your balance.");
    }

    @Override
    public String describe() {
        return "borrowing from the bank and paying it back";
    }
}
