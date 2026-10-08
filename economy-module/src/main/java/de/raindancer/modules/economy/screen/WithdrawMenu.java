package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Taking money out as coins — one, ten, a stack, or any number — or as a cheque. */
public final class WithdrawMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;

    public WithdrawMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Withdraw cash");
    }

    @Override
    public String breadcrumb() {
        return "Withdraw";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GOLD_INGOT, "<white>Balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));

        int column = 2;
        for (int count : new int[]{1, 10, 64}) {
            ItemStack icon = services.cash().coin();
            icon.setAmount(count);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Icons.name("<yellow>" + count + " " + (count == 1 ? currency.singular() : currency.plural())));
            meta.lore(List.of(Icons.loreLine("<yellow>Click<gray> to take them out")));
            icon.setItemMeta(meta);
            band(MenuLayout.RULES, column, icon, click -> {
                services.cash().withdraw(viewer, Money.of(count), false);
                refresh();
            });
            column += 2;
        }
        band(MenuLayout.LAND, 3, Icons.of(Material.NAME_TAG, "<yellow>Any number of coins",
                "<gray>Type how many."), click -> MoneyPrompt.ask(viewer, "How many coins?", currency, amount -> {
                    services.cash().withdraw(viewer, amount, false);
                    open();
                }, this::open));
        band(MenuLayout.LAND, 5, services.config().chequesEnabled(), Icons.of(Material.PAPER, "<yellow>A cheque",
                "<gray>One signed paper for exactly the amount you type."), BankMenu.OFF, click ->
                MoneyPrompt.ask(viewer, "Cheque for how much?", currency, amount -> {
                    services.cash().withdraw(viewer, amount, true);
                    open();
                }, this::open));
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Cash is an item: you can hand it over,", "drop it, lose it, or have it stolen.",
                "Right click it to pay it back in.");
    }

    @Override
    public String describe() {
        return "taking money out as coins and cheques";
    }
}
