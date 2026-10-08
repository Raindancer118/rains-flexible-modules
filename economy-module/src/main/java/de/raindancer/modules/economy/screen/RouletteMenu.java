package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuAnimation;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Game;
import de.raindancer.modules.economy.model.RouletteBet;
import de.raindancer.modules.economy.rules.RouletteRule;
import de.raindancer.modules.economy.service.GamblingService;
import de.raindancer.modules.economy.service.GameSounds;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.function.Consumer;

/**
 * European roulette. The wheel runs along the top as a strip of red, black and green under a pointer, ticks
 * past ever more slowly and stops on the pocket. Bets sit below; the spin is paid before the wheel moves.
 */
public final class RouletteMenu extends Menu implements IEconomyScreen, Bet.BetMenu {

    private static final int WHEEL_ROW = 1;

    private final EconomyServices services;
    private final Bet bet;
    private RouletteBet chosen = RouletteBet.on(RouletteBet.Kind.RED);
    /** Index into {@link RouletteRule#WHEEL} under the pointer. */
    private int at;
    private boolean spinning;
    private GamblingService.Wheel last;

    RouletteMenu(EconomyServices services, Player viewer, Menu parent, Bet bet) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.bet = bet.at(Game.ROULETTE);
    }

    public static void open(EconomyServices services, Player viewer, Menu parent, Money stake) {
        Bet bet = new Bet(services, viewer);
        if (stake != null) {
            bet.set(stake);
        }
        new RouletteMenu(services, viewer, parent, bet).open();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Roulette");
    }

    @Override
    public String breadcrumb() {
        return "Roulette";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.POINTED_DRIPSTONE, "<white>▼",
                spinning ? "<gray>The ball is rolling…" : last == null ? "<gray>Place a bet and spin."
                        : "<gray>Landed on <white>" + last.pocket()));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.PAPER, "<white>Bet: " + Mini.of(currency.render(bet.amount())),
                "<yellow>Click<gray> to type a bet"), click -> {
            if (!spinning) {
                MoneyPrompt.ask(viewer, "Bet how much?", currency, value -> {
                    bet.set(value);
                    open();
                }, this::open);
            }
        });

        for (int column = 0; column < 9; column++) {
            int index = Math.floorMod(at + column - 4, RouletteRule.WHEEL.size());
            int pocket = RouletteRule.WHEEL.get(index);
            cell(WHEEL_ROW, column, pocketIcon(pocket, column == 4), click -> { });
        }

        RouletteBet.Kind[] even = {RouletteBet.Kind.RED, RouletteBet.Kind.BLACK, RouletteBet.Kind.GREEN,
                RouletteBet.Kind.EVEN, RouletteBet.Kind.ODD, RouletteBet.Kind.LOW, RouletteBet.Kind.HIGH};
        Material[] evenIcons = {Material.RED_WOOL, Material.BLACK_WOOL, Material.LIME_WOOL, Material.QUARTZ_BLOCK,
                Material.SMOOTH_BASALT, Material.BIRCH_PLANKS, Material.DARK_OAK_PLANKS};
        for (int i = 0; i < even.length; i++) {
            RouletteBet option = RouletteBet.on(even[i]);
            band(MenuLayout.RULES, i + 1, betIcon(evenIcons[i], option), click -> choose(option));
        }
        band(MenuLayout.LAND, 2, betIcon(Material.WHITE_CONCRETE, RouletteBet.on(RouletteBet.Kind.FIRST_DOZEN)),
                click -> choose(RouletteBet.on(RouletteBet.Kind.FIRST_DOZEN)));
        band(MenuLayout.LAND, 3, betIcon(Material.LIGHT_GRAY_CONCRETE, RouletteBet.on(RouletteBet.Kind.SECOND_DOZEN)),
                click -> choose(RouletteBet.on(RouletteBet.Kind.SECOND_DOZEN)));
        band(MenuLayout.LAND, 4, betIcon(Material.GRAY_CONCRETE, RouletteBet.on(RouletteBet.Kind.THIRD_DOZEN)),
                click -> choose(RouletteBet.on(RouletteBet.Kind.THIRD_DOZEN)));
        RouletteBet number = chosen.kind() == RouletteBet.Kind.NUMBER ? chosen : RouletteBet.number(17);
        band(MenuLayout.LAND, 6, betIcon(Material.NAME_TAG, number), click -> {
            if (!spinning) {
                AnvilInput.open(viewer, "Which number, 0 to 36?", String.valueOf(number.number()),
                        Parsers.wholeNumber(0, 36), picked -> {
                            choose(RouletteBet.number(picked));
                            open();
                        }, this::open);
            }
        });

        toolbar(2, Icons.of(Material.RED_STAINED_GLASS_PANE, "<red>Halve the bet"), click -> {
            if (!spinning) {
                bet.set(Money.of(Math.max(1, bet.amount().minor() / 2)));
                refresh();
            }
        });
        toolbar(4, !spinning, Icons.of(Material.ENDER_PEARL, "<green>Spin",
                "<gray>" + Mini.of(currency.render(bet.amount())) + " <gray>on <white>" + chosen.label(),
                "<gray>Wins " + Mini.of(currency.render(services.gambling().rouletteWouldPay(bet.amount(), chosen)))),
                "The wheel is still turning.", click -> spin());
        toolbar(6, Icons.of(Material.LIME_STAINED_GLASS_PANE, "<green>Double the bet"), click -> {
            if (!spinning) {
                try {
                    bet.set(bet.amount().times(2));
                } catch (ArithmeticException tooBig) {
                    // Held at the maximum bet.
                }
                refresh();
            }
        });
    }

    private ItemStack pocketIcon(int pocket, boolean underPointer) {
        RouletteRule.Colour colour = services.gambling().colourOf(pocket);
        Material glass = switch (colour) {
            case RED -> Material.RED_STAINED_GLASS_PANE;
            case BLACK -> Material.BLACK_STAINED_GLASS_PANE;
            case GREEN -> Material.LIME_STAINED_GLASS_PANE;
        };
        String tint = switch (colour) {
            case RED -> "<red>";
            case BLACK -> "<dark_gray>";
            case GREEN -> "<green>";
        };
        ItemStack icon = Icons.of(underPointer ? switch (colour) {
            case RED -> Material.RED_CONCRETE;
            case BLACK -> Material.BLACK_CONCRETE;
            case GREEN -> Material.LIME_CONCRETE;
        } : glass, tint + (underPointer ? "<bold>" : "") + pocket);
        icon.setAmount(Math.max(1, pocket));
        return icon;
    }

    private ItemStack betIcon(Material material, RouletteBet option) {
        boolean selected = option.equals(chosen) || option.kind() == RouletteBet.Kind.NUMBER
                && chosen.kind() == RouletteBet.Kind.NUMBER;
        ItemStack icon = Icons.of(material, (selected ? "<yellow>▶ " : "<white>") + option.label(),
                "<gray>Pays " + Mini.of(services.currency().render(services.gambling().rouletteWouldPay(bet.amount(), option)))
                        + " <gray>on a win",
                selected ? "<yellow>Your bet" : "<yellow>Click<gray> to bet on this");
        if (selected) {
            ItemMeta meta = icon.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void choose(RouletteBet option) {
        if (spinning) {
            return;
        }
        chosen = option;
        services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.CHIPS);
        refresh();
    }

    private void spin() {
        if (spinning) {
            return;
        }
        services.gambling().roulette(viewer, bet.amount(), chosen).ifPresent(result -> {
            spinning = true;
            last = result;
            int target = RouletteRule.WHEEL.indexOf(result.pocket());
            int distance = Math.floorMod(target - at, RouletteRule.WHEEL.size());
            int frames = RouletteRule.WHEEL.size() + distance;
            MenuAnimation.play(services.plugin(), this, MenuAnimation.schedule(frames, 1, 5), frame -> {
                at = (at + 1) % RouletteRule.WHEEL.size();
                services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.WHEEL);
                refresh();
            }, () -> {
                at = target;
                spinning = false;
                services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.REEL_STOP);
                services.gambling().revealWheel(viewer, result);
                refresh();
            });
        });
    }

    @Override
    public void placeBand(int band, int column, ItemStack item, Consumer<InventoryClickEvent> handler) {
        band(band, column, item, handler);
    }

    @Override
    public void reopenAfterPrompt() {
        open();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Pick a bet, then spin. The pocket under the", "pointer when the wheel stops is the one.",
                "Red, black, even, odd, low and high pay double", "less the house edge; a dozen three times; a number 36.");
    }

    @Override
    public String describe() {
        return "European roulette, with a wheel that turns and stops";
    }
}
