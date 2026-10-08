package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuAnimation;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.service.GameSounds;
import de.raindancer.modules.economy.service.TableService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/** Hi-Lo: higher or lower than the card showing; every right guess grows the stake; cash out any time. */
public final class HiLoMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;
    private final Bet bet;
    private boolean flipping;

    HiLoMenu(EconomyServices services, Player viewer, Menu parent, Bet bet) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.bet = bet;
    }

    public static void open(EconomyServices services, Player viewer, Menu parent) {
        new HiLoMenu(services, viewer, parent, new Bet(services)).open();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Hi-Lo");
    }

    @Override
    public String breadcrumb() {
        return "Hi-Lo";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        TableService tables = services.tables();
        Optional<TableService.HiLo> current = tables.hiLo(viewer.getUniqueId());
        boolean playing = current.isPresent() && !current.get().finished;
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.PAPER, "<white>Bet: " + Mini.of(currency.render(bet.amount())),
                playing ? "<dark_gray>In play" : "<yellow>Click<gray> to type a bet"), click -> {
            if (!playing && !flipping) {
                MoneyPrompt.ask(viewer, "Bet how much?", currency, value -> {
                    bet.set(value);
                    open();
                }, this::open);
            }
        });

        current.ifPresent(game -> {
            if (game.last != null) {
                band(MenuLayout.RULES, 2, CardIcons.face(game.last));
            }
            band(MenuLayout.RULES, 4, flipping ? CardIcons.back() : CardIcons.face(game.showing));
            Money worth = game.stake.share(game.multiplier);
            set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.EMERALD, "<green>Worth " + Mini.of(currency.render(worth)),
                    "<gray>" + String.format("%.2f", game.multiplier) + "× after " + game.streak + " right"));
        });

        if (playing && !flipping) {
            TableService.HiLo game = current.get();
            int[] left = tables.ranksLeft(viewer.getUniqueId());
            double higher = tables.hiLoRule().chance(game.showing.rank(), true, left);
            double lower = tables.hiLoRule().chance(game.showing.rank(), false, left);
            toolbar(2, higher > 0, Icons.of(Material.LIME_CONCRETE, "<green>Higher",
                    "<gray>Chance " + Math.round(higher * 100) + "%",
                    "<gray>×" + String.format("%.2f", tables.hiLoRule().step(higher, tables.edge()))),
                    "Nothing is higher.", click -> guess(true));
            toolbar(4, game.streak > 0, Icons.of(Material.GOLD_BLOCK, "<gold>Cash out",
                    "<gray>Take " + Mini.of(currency.render(game.stake.share(game.multiplier)))),
                    "Guess right once first.", click -> {
                        tables.cashOutHiLo(viewer);
                        refresh();
                    });
            toolbar(6, lower > 0, Icons.of(Material.RED_CONCRETE, "<red>Lower",
                    "<gray>Chance " + Math.round(lower * 100) + "%",
                    "<gray>×" + String.format("%.2f", tables.hiLoRule().step(lower, tables.edge()))),
                    "Nothing is lower.", click -> guess(false));
        } else if (!flipping) {
            toolbar(4, Icons.of(Material.EMERALD, "<green>Start", "<gray>Draw the first card for "
                    + Mini.of(currency.render(bet.amount()))), click -> {
                tables.startHiLo(viewer, bet.amount());
                services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.CARD);
                refresh();
            });
        }
    }

    private void guess(boolean higher) {
        if (flipping) {
            return;
        }
        services.tables().guess(viewer, higher);
        flipping = true;
        MenuAnimation.play(services.plugin(), this, MenuAnimation.schedule(1, 8, 8), frame ->
                services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.CARD), () -> {
            flipping = false;
            refresh();
        });
        refresh();
    }

    @Override
    public void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        super.handleClose(event);
        services.tables().cashOutHiLo(viewer);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Will the next card be higher or lower?", "The same rank loses either way.",
                "Every right guess grows your stake. Cash out whenever —", "closing the window cashes out too.");
    }

    @Override
    public String describe() {
        return "hi-lo with the dealer's shoe";
    }
}
