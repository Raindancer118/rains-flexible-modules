package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.service.GameSounds;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuAnimation;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.SlotSymbol;
import de.raindancer.modules.economy.service.GamblingService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Three reels in the middle of the window. Each spins through a strip of symbols ending on its result,
 * and they stop left to right, the last one slowest. The spin is paid before the reels move.
 */
public final class SlotsMenu extends Menu implements IEconomyScreen, Bet.BetMenu {

    private static final int[] REEL_COLUMNS = {2, 4, 6};
    /** How long each reel's strip is: the later reels spin longer, so they stop one after another. */
    private static final int[] STRIP = {14, 20, 26};

    private final EconomyServices services;
    private final Bet bet;
    private final List<List<SlotSymbol>> strips = new ArrayList<>();
    private final int[] position = {1, 1, 1};
    private boolean spinning;
    private GamblingService.Spin last;

    SlotsMenu(EconomyServices services, Player viewer, Menu parent, Bet bet) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.bet = bet;
        for (int reel = 0; reel < 3; reel++) {
            strips.add(randomStrip(3, null));
        }
    }

    private static List<SlotSymbol> randomStrip(int length, SlotSymbol ending) {
        List<SlotSymbol> strip = new ArrayList<>();
        SlotSymbol[] all = SlotSymbol.values();
        for (int i = 0; i < length + 1; i++) {
            strip.add(all[ThreadLocalRandom.current().nextInt(all.length)]);
        }
        if (ending != null) {
            strip.set(length - 1, ending);
        }
        return strip;
    }

    public static void open(EconomyServices services, Player viewer, Menu parent) {
        new SlotsMenu(services, viewer, parent, new Bet(services)).open();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Slot machine");
    }

    @Override
    public String breadcrumb() {
        return "Slots";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_RIGHT, paytable());

        for (int reel = 0; reel < 3; reel++) {
            List<SlotSymbol> strip = strips.get(reel);
            int at = position[reel];
            for (int row = 1; row <= 3; row++) {
                int index = Math.floorMod(at + row - 2, strip.size());
                SlotSymbol symbol = strip.get(index);
                boolean line = row == 2;
                set(row * 9 + REEL_COLUMNS[reel], Icons.of(symbol.icon(),
                        (line ? "<white>" : "<dark_gray>") + name(symbol)));
            }
        }
        Material lineColour = spinning || last == null ? Material.YELLOW_STAINED_GLASS_PANE
                : last.won() ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        String lineText = spinning || last == null ? "<yellow>Pay line"
                : last.won() ? "<green>Won " + Mini.of(currency.render(last.payout()))
                : "<red>Lost " + Mini.of(currency.render(last.stake()));
        set(2 * 9 + 1, Icons.of(lineColour, lineText));
        set(2 * 9 + 7, Icons.of(lineColour, lineText));

        toolbar(2, Icons.of(Material.RED_STAINED_GLASS_PANE, "<red>Halve the bet"), click -> {
            if (!spinning) {
                bet.set(de.raindancer.core.social.economy.Money.of(Math.max(1, bet.amount().minor() / 2)));
                refresh();
            }
        });
        toolbar(4, !spinning, Icons.of(Material.LEVER, "<green>Spin",
                "<gray>For " + Mini.of(currency.render(bet.amount()))), "The reels are still turning.",
                click -> spin());
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

    private ItemStack paytable() {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Three alike pays, per bet:");
        for (SlotSymbol symbol : SlotSymbol.values()) {
            double times = services.gambling().slotsMultiplier(List.of(symbol, symbol, symbol));
            lore.add("<white>" + name(symbol) + " ×3 <gray>→ <yellow>" + String.format("%.1f", times) + "×");
        }
        lore.add("<gray>Two netherite → <yellow>" + String.format("%.1f", services.gambling().slotsMultiplier(
                List.of(SlotSymbol.NETHERITE, SlotSymbol.NETHERITE, SlotSymbol.COAL))) + "×");
        lore.add("<gray>One netherite → <yellow>" + String.format("%.2f", services.gambling().slotsMultiplier(
                List.of(SlotSymbol.NETHERITE, SlotSymbol.COAL, SlotSymbol.IRON))) + "×");
        lore.add("<gray>Two alike side by side → <yellow>" + String.format("%.2f", services.gambling().slotsMultiplier(
                List.of(SlotSymbol.COAL, SlotSymbol.COAL, SlotSymbol.IRON))) + "×");
        return Icons.of(Material.BOOK, "<gold>What pays", lore);
    }

    private static String name(SlotSymbol symbol) {
        return symbol.name().charAt(0) + symbol.name().substring(1).toLowerCase();
    }

    private void spin() {
        if (spinning) {
            return;
        }
        services.gambling().spin(viewer, bet.amount()).ifPresent(result -> {
            spinning = true;
            last = result;
            for (int reel = 0; reel < 3; reel++) {
                // Each strip ends on the reel's result, two from its end so the rows around it are filled.
                List<SlotSymbol> strip = randomStrip(STRIP[reel], result.reels().get(reel));
                strips.set(reel, strip);
                position[reel] = 1;
            }
            int frames = STRIP[2] - 2;
            MenuAnimation.play(services.plugin(), this, MenuAnimation.schedule(frames, 1, 3), frame -> {
                for (int reel = 0; reel < 3; reel++) {
                    int stopAt = STRIP[reel] - 1;
                    if (position[reel] < stopAt) {
                        position[reel]++;
                        if (position[reel] == stopAt) {
                            services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.REEL_STOP);
                        }
                    }
                }
                services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.TICK);
                refresh();
            }, () -> {
                for (int reel = 0; reel < 3; reel++) {
                    position[reel] = STRIP[reel] - 1;
                }
                spinning = false;
                services.gambling().revealSpin(viewer, result);
                refresh();
            });
        });
    }

    @Override
    public void placeBand(int band, int column, ItemStack item, Consumer<InventoryClickEvent> handler) {
        if (item != null) {
            band(band, column, item, handler);
        }
    }

    @Override
    public void reopenAfterPrompt() {
        open();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Pull the lever. The middle row is the pay line.",
                "The book in the corner says what pays.");
    }

    @Override
    public String describe() {
        return "the slot machine, with reels that spin and stop in turn";
    }
}
