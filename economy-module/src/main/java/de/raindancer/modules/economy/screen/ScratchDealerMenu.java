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

import java.util.List;

/** The scratch card seller: a card, five or ten. Scratching is done by right clicking a card in hand. */
public final class ScratchDealerMenu extends Menu implements IEconomyScreen {

    private static final int[] COUNTS = {1, 5, 10};

    private final EconomyServices services;

    public ScratchDealerMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Scratch cards");
    }

    @Override
    public String breadcrumb() {
        return "Scratch cards";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        Money price = services.config().scratchPriceMoney();
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.MAP, "<gold>Scratch cards",
                "<gray>" + Mini.of(currency.render(price)) + "<gray> a card.",
                "<gray>Right click one in your hand to scratch it."));
        for (int i = 0; i < COUNTS.length; i++) {
            int count = COUNTS[i];
            band(MenuLayout.RULES, 3 + i, Icons.of(Material.MAP, "<yellow>Buy " + count,
                    "<gray>For " + Mini.of(currency.render(price.times(count)))), click -> {
                services.scratch().buy(viewer, count);
                refresh();
            });
        }
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Buy cards here, scratch them anywhere:", "right click a card in your hand.");
    }

    @Override
    public String describe() {
        return "the scratch card seller";
    }
}
