package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Game;
import de.raindancer.modules.economy.model.NaturalPay;
import de.raindancer.modules.economy.util.Mini;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The owner's view of the casino: the house edge and bet limits for all games, and every game with what it
 * keeps and who may bet how much. A click opens that game's own settings; a right click switches it.
 */
public final class GamesMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;

    GamesMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Casino");
    }

    @Override
    public String breadcrumb() {
        return "Casino";
    }

    @Override
    protected void render() {
        if (!viewer.hasPermission(PermissionNodes.ADMIN)) {
            set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.BARRIER, "<red>Staff only"));
            return;
        }
        EconomySettings live = services.config();
        Currency currency = services.currency();
        boolean on = live.gamblingEnabled();
        set(MenuLayout.HEADER_LEFT, Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                on ? "<green>Gambling is on" : "<red>Gambling is off", "<gray>Every game at once.",
                "<yellow>Click<gray> to switch"), click -> {
            services.store().cycle("features.gambling");
            services.store().trySave();
            refresh();
        });
        Money loss = live.dailyLossLimitMoney();
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GOLD_BLOCK, "<gold>The whole casino",
                "<gray>The house keeps <white>" + CasinoMenu.percent(live.houseEdge()) + "%",
                "<gray>Bets: " + bets(currency, live.minBetMoney()),
                "<gray>Most lost a day: " + (loss.isPositive() ? Mini.of(currency.render(loss)) : "<white>no limit"),
                "<dark_gray>A game's own settings win over these.",
                "", "<yellow>Click<gray> to change"),
                click -> AdminMenu.settings(services, viewer, this, "economy/gambling"));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.FILLED_MAP, "<aqua>Lottery pot",
                Mini.of(currency.render(services.lottery().pot()))));

        Game[] games = Game.values();
        for (int i = 0; i < games.length; i++) {
            Game game = games[i];
            int band = i < 7 ? MenuLayout.RULES : MenuLayout.LAND;
            int column = i < 7 ? 1 + i : 2 + (i - 7);
            band(band, column, icon(live, currency, game), click -> {
                if (click.isRightClick()) {
                    services.store().cycle("features." + game.key());
                    services.store().trySave();
                    refresh();
                } else {
                    AdminMenu.settings(services, viewer, this, game.settingsPath());
                }
            });
        }
    }

    private org.bukkit.inventory.ItemStack icon(EconomySettings live, Currency currency, Game game) {
        boolean open = live.gameOn(game);
        List<String> lore = new ArrayList<>();
        lore.add(open ? "<green>Open" : "<red>Closed");
        if (game.edge()) {
            lore.add("<gray>The house keeps <white>" + CasinoMenu.percent(live.edge(game)) + "%"
                    + (live.edge(game) == live.houseEdge() ? " <dark_gray>(the casino's)" : ""));
        }
        if (game.bets()) {
            lore.add("<gray>Bets: " + bets(currency, live.minBet(game)));
        }
        switch (game) {
            case BLACKJACK -> {
                lore.add("<gray>A natural pays <white>" + naturalPays(live.naturalPays()));
                lore.add("<gray>The dealer " + (live.dealerHitsSoft17() ? "hits" : "stands on") + " a soft 17");
            }
            case BACCARAT -> {
                lore.add("<gray>A tie pays <white>" + live.baccaratTiePays() + " to 1");
                lore.add("<gray>Banker wins pay <white>" + CasinoMenu.percent(live.baccaratCommission()) + "%<gray> commission");
            }
            case CRASH -> lore.add("<gray>Goes no higher than <white>×" + live.crashMost());
            case SCRATCH -> lore.add("<gray>A card costs " + Mini.of(currency.render(live.scratchPriceMoney())));
            case LOTTERY -> {
                lore.add("<gray>A ticket costs " + Mini.of(currency.render(live.ticketPriceMoney())));
                lore.add("<gray>Keeps <white>" + CasinoMenu.percent(live.lotteryCut()) + "%<gray> of every ticket");
            }
            default -> {
            }
        }
        lore.add("");
        lore.add("<yellow>Click<gray> for its settings");
        lore.add("<yellow>Right click<gray> to " + (open ? "close" : "open") + " it");
        return Icons.of(open ? game.icon() : Material.GRAY_DYE, (open ? "<yellow>" : "<gray>") + game.title(), lore);
    }

    private static String bets(Currency currency, Money least) {
        return Mini.of(currency.render(least)) + "<gray> to <white>any amount";
    }

    private static String naturalPays(NaturalPay pays) {
        return switch (pays) {
            case THREE_TO_TWO -> "3 to 2";
            case SIX_TO_FIVE -> "6 to 5";
            case EVEN_MONEY -> "even money";
        };
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Every game keeps its own edge and bets,", "or the casino's when it has none of its own.");
    }

    @Override
    public String describe() {
        return "the casino's games, what each keeps and who may bet how much";
    }
}
