package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Denomination;
import de.raindancer.modules.economy.model.Form;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Taking money out as the pieces themselves: one of a kind per click, ten with a shift click. */
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
        Money balance = services.economy().balance(viewer.getUniqueId());
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GOLD_INGOT, "<white>Balance",
                Mini.of(currency.render(balance))));

        List<Denomination> pieces = services.cash().denominations();
        int column = 1;
        for (Denomination denomination : pieces.reversed()) {
            if (column > 7) {
                break;
            }
            ItemStack icon = services.cash().piece(denomination, null);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(Icons.loreLine("<gray>" + (denomination.form() == Form.NOTE ? "A numbered banknote" : "A coin")));
            lore.add(Component.empty());
            lore.add(Icons.loreLine("<yellow>Click<gray> to take one out"));
            lore.add(Icons.loreLine("<yellow>Shift click<gray> to take ten"));
            meta.lore(lore);
            icon.setItemMeta(meta);
            band(MenuLayout.RULES, column++, icon, click -> {
                services.cash().withdraw(viewer, denomination.value().times(click.isShiftClick() ? 10 : 1), false);
                refresh();
            });
        }
        band(MenuLayout.LAND, 3, Icons.of(Material.NAME_TAG, "<yellow>Any amount",
                "<gray>Type it; it comes out in the fewest pieces."), click ->
                MoneyPrompt.ask(viewer, "Withdraw how much?", currency, amount -> {
                    services.cash().withdraw(viewer, amount, false);
                    open();
                }, this::open));
        band(MenuLayout.LAND, 5, Icons.of(Material.PAPER, "<yellow>A cheque",
                "<gray>One note for exactly the amount you type."), click ->
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
        return "taking money out as coins, notes and cheques";
    }
}
