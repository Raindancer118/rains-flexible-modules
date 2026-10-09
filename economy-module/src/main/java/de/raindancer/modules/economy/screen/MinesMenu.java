package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Game;
import de.raindancer.modules.economy.rules.MinesRule;
import de.raindancer.modules.economy.service.TableService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/** Mines: a five-by-five field in the middle of the window; clear tiles, cash out before you hit one. */
public final class MinesMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;
    private final Bet bet;
    private int mineCount = 3;

    MinesMenu(EconomyServices services, Player viewer, Menu parent, Bet bet) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.bet = bet.at(Game.MINES);
    }

    public static void open(EconomyServices services, Player viewer, Menu parent) {
        new MinesMenu(services, viewer, parent, new Bet(services, viewer)).open();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Mines");
    }

    @Override
    public String breadcrumb() {
        return "Mines";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        TableService tables = services.tables();
        Optional<TableService.Mines> current = tables.mines(viewer.getUniqueId());
        boolean playing = current.isPresent() && !current.get().finished;

        for (int tile = 0; tile < MinesRule.TILES; tile++) {
            int row = tile / 5;
            int column = 2 + tile % 5;
            int index = tile;
            if (current.isEmpty()) {
                set(row * 9 + column, Icons.of(Material.GRAY_STAINED_GLASS, "<gray>?"));
                continue;
            }
            TableService.Mines field = current.get();
            if (field.cleared.contains(tile)) {
                set(row * 9 + column, Icons.of(Material.EMERALD, "<green>Safe"));
            } else if (field.finished && field.mines.contains(tile)) {
                set(row * 9 + column, Icons.of(tile == field.boom ? Material.TNT : Material.COAL_BLOCK,
                        tile == field.boom ? "<red>Boom!" : "<dark_gray>A mine"));
            } else if (playing) {
                set(row * 9 + column, Icons.of(Material.GRAY_STAINED_GLASS, "<gray>?", "<yellow>Click<gray> to clear it"),
                        click -> {
                            tables.pick(viewer, index);
                            refresh();
                        });
            } else {
                set(row * 9 + column, Icons.of(Material.LIGHT_GRAY_STAINED_GLASS, "<gray>Safe, never cleared"));
            }
        }

        set(0, Icons.of(Material.GOLD_INGOT, "<white>Balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(9, bet.slip(!playing), click -> bet.onSlip(click, !playing, this::open, this::refresh));
        set(18, Icons.of(Material.TNT, "<white>Mines: " + mineCount, playing ? "<dark_gray>In play"
                : "<yellow>Click<gray> for more, <yellow>right click<gray> for fewer"), click -> {
            if (!playing) {
                mineCount = Math.max(1, Math.min(24, mineCount + (click.isRightClick() ? -1 : 1)));
                refresh();
            }
        });
        current.ifPresent(field -> set(8, Icons.of(Material.EMERALD, "<green>Worth "
                + Mini.of(currency.render(tables.minesWorth(field))), "<gray>" + field.cleared.size() + " cleared")));
        if (playing) {
            TableService.Mines field = current.get();
            set(17, Icons.of(Material.GOLD_BLOCK, "<gold>Cash out", "<gray>Take "
                    + Mini.of(currency.render(tables.minesWorth(field)))), click -> {
                tables.cashOutMines(viewer);
                refresh();
            });
        } else {
            set(17, Icons.of(Material.LIME_CONCRETE, "<green>Start", "<gray>" + mineCount + " mines, next tile ×"
                    + String.format("%.2f", tables.minesRule().multiplier(mineCount, 1, tables.edge(Game.MINES)))), click -> {
                tables.startMines(viewer, bet.amount(), mineCount);
                refresh();
            });
        }
    }

    @Override
    public void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        super.handleClose(event);
        services.tables().cashOutMines(viewer);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Clear tiles without hitting a mine.", "Every safe tile raises what you can take.",
                "More mines, bigger steps. Closing the window cashes out.");
    }

    @Override
    public String describe() {
        return "a mines field";
    }
}
