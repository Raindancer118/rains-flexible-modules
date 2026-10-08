package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.service.GameSounds;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuAnimation;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Game;
import de.raindancer.modules.economy.service.GamblingService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Over or under a number from 1 to 100. A track across the window shows which rolls win in green; while
 * the dice tumble, a marker runs along it and stops on the roll.
 */
public final class DiceMenu extends Menu implements IEconomyScreen, Bet.BetMenu {

    private static final int FRAMES = 22;
    private static final int TRACK_ROW = 3;

    private final EconomyServices services;
    private final Bet bet;
    private boolean over = true;
    private int target = 50;
    private boolean rolling;
    private int shown = 50;
    private GamblingService.Roll last;

    DiceMenu(EconomyServices services, Player viewer, Menu parent, Bet bet) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.bet = bet.at(Game.DICE);
    }

    /** Opens with this bet and number; with both given, the dice are rolled at once. */
    public static void open(EconomyServices services, Player viewer, Menu parent, Money stake, Boolean over,
                            Integer target) {
        Bet bet = new Bet(services, viewer);
        if (stake != null) {
            bet.set(stake);
        }
        DiceMenu menu = new DiceMenu(services, viewer, parent, bet);
        if (over != null) {
            menu.over = over;
        }
        if (target != null) {
            menu.target = Math.max(1, Math.min(100, target));
            menu.shown = menu.target;
        }
        menu.open();
        if (over != null && target != null) {
            menu.roll();
        }
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Dice");
    }

    @Override
    public String breadcrumb() {
        return "Dice";
    }

    /** Which of the nine track cells a roll falls in. */
    static int cellOf(int roll) {
        return Math.max(0, Math.min(8, (roll - 1) * 9 / 100));
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        GamblingService games = services.gambling();
        boolean valid = games.diceValid(over, target);
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_SUBJECT, Icons.of(over ? Material.LIME_DYE : Material.ORANGE_DYE,
                "<white>Roll " + (over ? "over " : "under ") + target,
                valid ? "<gray>Chance: " + Math.round(games.diceChance(over, target) * 100) + "%" : "<red>Not a bet the house takes",
                valid ? "<gray>Wins " + Mini.of(currency.render(games.diceWouldPay(bet.amount(), over, target))) : "",
                "<yellow>Click<gray> to switch over and under"), click -> {
            if (!rolling) {
                over = !over;
                refresh();
            }
        });
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.PAPER, "<white>Bet: " + Mini.of(currency.render(bet.amount()))));

        int[] steps = {-10, -1, 1, 10};
        int[] columns = {1, 2, 6, 7};
        for (int i = 0; i < steps.length; i++) {
            int step = steps[i];
            band(MenuLayout.WHO, columns[i], Icons.of(step < 0 ? Material.RED_STAINED_GLASS_PANE
                    : Material.LIME_STAINED_GLASS_PANE, (step < 0 ? "<red>" : "<green>+") + step), click -> {
                if (!rolling) {
                    target = Math.max(1, Math.min(100, target + step));
                    refresh();
                }
            });
        }
        band(MenuLayout.WHO, 4, Icons.of(Material.TARGET, "<white>Your number: " + target));

        ItemStack face = Icons.of(Material.WHITE_WOOL, (rolling ? "<yellow>" : "<white>") + shown,
                last != null && !rolling ? (last.won() ? "<green>You won " + Mini.of(currency.render(last.payout()))
                        : "<red>You lost " + Mini.of(currency.render(last.stake()))) : "");
        face.setAmount(Math.max(1, Math.min(64, shown)));
        band(MenuLayout.RULES, 4, face);

        int marker = cellOf(shown);
        for (int cell = 0; cell < 9; cell++) {
            int middle = Math.min(100, cell * 100 / 9 + 6);
            boolean wins = games.diceValid(over, target) && (over ? middle > target : middle < target);
            Material glass = cell == marker && (rolling || last != null) ? Material.WHITE_STAINED_GLASS_PANE
                    : wins ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            cell(TRACK_ROW, cell, Icons.of(glass, wins ? "<green>Wins" : "<red>Loses",
                    "<gray>" + (cell * 100 / 9 + 1) + " to " + ((cell + 1) * 100 / 9)), click -> { });
        }

        toolbar(4, valid && !rolling, Icons.of(Material.SLIME_BALL, "<green>Roll",
                        "<gray>For " + Mini.of(currency.render(bet.amount()))),
                rolling ? "Rolling…" : "Pick a number the house takes.", click -> roll());
    }

    private void roll() {
        if (rolling) {
            return;
        }
        services.gambling().dice(viewer, bet.amount(), over, target).ifPresent(result -> {
            rolling = true;
            last = result;
            MenuAnimation.play(services.plugin(), this, MenuAnimation.schedule(FRAMES, 1, 4), frame -> {
                shown = frame == FRAMES - 1 ? result.roll() : ThreadLocalRandom.current().nextInt(1, 101);
                services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.DICE);
                refresh();
            }, () -> {
                rolling = false;
                shown = result.roll();
                services.gambling().revealRoll(viewer, result);
                refresh();
            });
        });
    }

    @Override
    public void placeBand(int band, int column, ItemStack item, Consumer<InventoryClickEvent> handler) {
        band(band, column, item, click -> {
            if (!rolling) {
                handler.accept(click);
            }
        });
    }

    @Override
    public void reopenAfterPrompt() {
        open();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Pick a number and whether the roll lands over", "or under it. The longer the odds, the more",
                "a win pays. Green on the track wins.");
    }

    @Override
    public String describe() {
        return "a roll of the dice against the house, with a running marker";
    }
}
