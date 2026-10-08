package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.service.ExperienceService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Experience levels in the shop: each button shows what that many more levels cost right now. Selling is in /sell. */
public final class ExperienceMenu extends Menu implements IEconomyScreen {

    private static final int[] BUY = {1, 5, 10, 30};

    private final EconomyServices services;

    ExperienceMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Experience");
    }

    @Override
    public String breadcrumb() {
        return "Experience";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        ExperienceService experience = services.experience();
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.EXPERIENCE_BOTTLE, "<green>Level " + viewer.getLevel(),
                "<gray>" + viewer.calculateTotalExperiencePoints() + " points",
                "<gray>A point costs " + Mini.of(currency.render(services.config().xpBuyMoney()))
                        + "<gray>, sells for " + Mini.of(currency.render(services.config().xpSellMoney()))));
        for (int i = 0; i < BUY.length; i++) {
            int levels = BUY[i];
            ExperienceService.Quote quote = experience.buying(viewer, levels);
            band(MenuLayout.RULES, 1 + i * 2, Icons.of(Material.LIME_DYE, "<green>Buy " + levels + " level(s)",
                    "<gray>" + quote.points() + " points for " + Mini.of(currency.render(quote.money()))), click -> {
                experience.buy(viewer, levels);
                refresh();
            });
        }
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Experience, bought by the point:", "higher levels hold more points, so cost more.",
                "Sell experience in /sell.");
    }

    @Override
    public String describe() {
        return "buying and selling experience levels";
    }
}
