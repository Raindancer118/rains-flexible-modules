package de.raindancer.modules.economy.screen;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Debts;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.SupplySettings;
import de.raindancer.modules.economy.model.Fund;
import de.raindancer.modules.economy.service.FundService.Donation;
import de.raindancer.modules.economy.util.Mini;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The server's money, as a player meets it: the treasury, community funds to give to, repairing what you
 * hold, bet insurance, season points and what you owe. Everything the owner switched off is shown greyed.
 */
public final class SupplyMenu extends Menu implements IEconomyScreen {

    private static final int MOST_FUNDS = 7;

    private final EconomyServices services;

    public SupplyMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>The server's money");
    }

    @Override
    public String breadcrumb() {
        return "The server's money";
    }

    @Override
    protected void render() {
        SupplySettings live = services.supply().current();
        Currency currency = services.currency();
        var supply = services.supply().snapshot();
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.BEACON, "<aqua>The server's money",
                supply.capped() ? "<gray>A hard cap: nothing is printed." : "<gray>No cap on money.",
                supply.capped() ? "<gray>Payouts come out of the treasury;" : "",
                supply.capped() ? "<gray>fees and taxes go back into it." : ""));
        Money owed = Debts.owed(viewer.getUniqueId());
        set(MenuLayout.HEADER_RIGHT, Icons.of(owed.isPositive() ? Material.LEAD : Material.STRING,
                owed.isPositive() ? "<red>You owe the server" : "<white>You owe nothing",
                owed.isPositive() ? Mini.of(currency.render(owed)) : "",
                owed.isPositive() && live.debtCollect()
                        ? "<gray>" + live.debtSharePercent() + "% of every payout goes toward it." : "",
                owed.isPositive() ? "<gray>Pay it off where it came from (/debt, /claim upkeep)." : ""));

        boolean seeTreasury = live.treasuryInfo() || viewer.hasPermission(PermissionNodes.ADMIN);
        band(MenuLayout.WHO, 2, seeTreasury, Icons.of(Material.IRON_BARS, "<white>The treasury",
                supply.capped() ? "<gray>Holds " + Mini.of(currency.render(supply.treasury())) + " of "
                        + Mini.of(currency.render(supply.cap())) : "<gray>Money out there: "
                        + Mini.of(currency.render(supply.circulating())),
                "<yellow>Click<gray> for where money came from this week"), BankMenu.OFF, click -> {
            viewer.closeInventory();
            viewer.performCommand("treasury");
        });
        boolean repairOn = live.repair() && viewer.hasPermission(PermissionNodes.REPAIR);
        band(MenuLayout.WHO, 4, repairOn, Icons.of(Material.ANVIL, "<white>Repair what you hold",
                "<gray>A share of what it is worth,", "<gray>scaled by how worn it is.",
                "<yellow>Click<gray> for the price"), live.repair() ? BankMenu.NOT_ALLOWED : BankMenu.OFF, click -> {
            viewer.closeInventory();
            services.repair().offer(viewer);
        });
        boolean offered = services.gambling().insuranceOffered();
        boolean insured = services.gambling().insures(viewer.getUniqueId());
        band(MenuLayout.WHO, 6, offered, Icons.of(insured ? Material.TOTEM_OF_UNDYING : Material.ARMOR_STAND,
                "<white>Bet insurance: " + (insured ? "<green>on" : "<gray>off"),
                "<gray>Every stake costs " + live.gambleInsurancePremium() + "% more;",
                "<gray>a lost stake pays " + live.gambleInsurancePayback() + "% of it back.",
                "<yellow>Click<gray> to switch it " + (insured ? "off" : "on")), BankMenu.OFF, click -> {
            services.gambling().toggleInsurance(viewer);
            refresh();
        });
        band(MenuLayout.WHO, 8, live.seasons() && viewer.hasPermission(PermissionNodes.SEASON),
                Icons.of(Material.NETHER_STAR, "<white>Your season points", "<yellow>Click<gray> to see them"),
                BankMenu.OFF, click -> {
                    viewer.closeInventory();
                    viewer.performCommand("season");
                });

        List<Fund> running = live.funds() ? services.funds().running() : List.of();
        if (!live.funds()) {
            band(MenuLayout.RULES, 4, false, Icons.of(Material.BEACON, "<white>Community funds"), BankMenu.OFF,
                    click -> { });
            return;
        }
        if (running.isEmpty()) {
            band(MenuLayout.RULES, 4, Icons.of(Material.GLASS_BOTTLE, "<gray>No fund is collecting",
                    "<dark_gray>Staff start one with /eco fund start."));
        }
        for (int index = 0; index < Math.min(MOST_FUNDS, running.size()); index++) {
            Fund fund = running.get(index);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + Mini.of(currency.render(fund.raised())) + " of " + Mini.of(currency.render(fund.target()))
                    + " <dark_gray>(" + (int) Math.floor(fund.progress() * 100) + "%)");
            services.funds().effectOf(fund).ifPresent(effect -> {
                if (effect instanceof de.raindancer.modules.economy.rules.FundRule.Effect.Boost boost) {
                    lore.add("<green>When full: everything pays " + boost.percent() + "% more for " + boost.hours() + "h");
                }
            });
            lore.add("<dark_gray>What you give is gone for good — it pays for this.");
            lore.add("<yellow>Click<gray> to give");
            band(MenuLayout.RULES, index + 1, viewer.hasPermission(PermissionNodes.FUND),
                    Icons.of(Material.BEACON, "<yellow>" + fund.name(), lore), BankMenu.NOT_ALLOWED,
                    click -> MoneyPrompt.ask(viewer, "Give how much to " + fund.name() + "?", currency, amount ->
                            Scheduling.async(services.plugin(), () -> {
                                Donation done = services.funds().donate(viewer.getUniqueId(), fund.name(), amount);
                                Scheduling.entity(services.plugin(), viewer, () -> {
                                    switch (done.outcome()) {
                                        case GIVEN, FILLED -> services.messages().send(viewer, "economy.fund.given",
                                                "amount", currency.render(done.given()), "name", fund.name());
                                        case OFF -> services.messages().send(viewer, "economy.fund.off");
                                        case NO_SUCH_FUND -> services.messages().send(viewer, "economy.fund.no-such");
                                        case NOT_ENOUGH -> services.messages().send(viewer, "economy.fund.not-enough");
                                        case REFUSED -> services.messages().send(viewer, "economy.fund.refused");
                                    }
                                    open();
                                });
                            }), this::open));
        }
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Everything here is about the server's money as a whole.",
                "Greyed buttons are switched off on this server.");
    }

    @Override
    public String describe() {
        return "the server's money: the treasury, funds, repair, bet insurance, season points";
    }
}
