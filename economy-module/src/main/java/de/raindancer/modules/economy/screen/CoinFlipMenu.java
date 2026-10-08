package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.service.GameSounds;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuAnimation;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.service.GamblingService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Heads or tails, with a coin that spins in the middle of the window and slows down until it lands.
 * The flip is decided and paid on the click; the spin only shows it.
 */
public final class CoinFlipMenu extends Menu implements IEconomyScreen, Bet.BetMenu {

    private static final int FRAMES = 18;

    private final EconomyServices services;
    private final Bet bet;
    private boolean spinning;
    private boolean showingHeads = true;
    private GamblingService.Flip last;

    CoinFlipMenu(EconomyServices services, Player viewer, Menu parent, Bet bet) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.bet = bet;
    }

    /** Opens with this bet; with a call, the coin is flipped at once. */
    public static void open(EconomyServices services, Player viewer, Menu parent, Money stake, Boolean call) {
        Bet bet = new Bet(services);
        if (stake != null) {
            bet.set(stake);
        }
        CoinFlipMenu menu = new CoinFlipMenu(services, viewer, parent, bet);
        menu.open();
        if (call != null) {
            menu.play(call);
        }
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Coin flip");
    }

    @Override
    public String breadcrumb() {
        return "Coin flip";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        bet.buttons(this, MenuLayout.WHO, viewer);

        String wins = "<gray>Wins " + Mini.of(currency.render(services.gambling().flipWouldPay(bet.amount())));
        band(MenuLayout.RULES, 1, Icons.of(Material.GOLD_BLOCK, "<gold>Call heads", wins), click -> play(true));
        band(MenuLayout.RULES, 4, coin(), click -> { });
        band(MenuLayout.RULES, 7, Icons.of(Material.IRON_BLOCK, "<white>Call tails", wins), click -> play(false));
        if (last != null && !spinning) {
            band(MenuLayout.LAND, 4, Icons.of(last.won() ? Material.EMERALD : Material.BARRIER,
                    last.won() ? "<green>You won " + Mini.of(currency.render(last.payout()))
                            : "<red>You lost " + Mini.of(currency.render(last.stake())),
                    "<gray>It landed " + (last.landedHeads() ? "heads" : "tails") + "."));
        }
    }

    private ItemStack coin() {
        if (spinning) {
            return Icons.of(showingHeads ? Material.GOLD_BLOCK : Material.IRON_BLOCK, "<yellow>…");
        }
        if (last == null) {
            return Icons.of(Material.SUNFLOWER, "<yellow>The coin", "<gray>Call it.");
        }
        return Icons.of(last.landedHeads() ? Material.GOLD_BLOCK : Material.IRON_BLOCK,
                last.landedHeads() ? "<gold>Heads!" : "<white>Tails!");
    }

    private void play(boolean heads) {
        if (spinning) {
            return;
        }
        services.gambling().flip(viewer, bet.amount(), heads).ifPresent(flip -> {
            spinning = true;
            last = flip;
            MenuAnimation.play(services.plugin(), this, MenuAnimation.schedule(FRAMES, 1, 5), frame -> {
                // The last frame shows the side it landed on: count back from it, alternating.
                boolean fromEnd = (FRAMES - 1 - frame) % 2 == 0;
                showingHeads = fromEnd == flip.landedHeads();
                services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.COIN);
                refresh();
            }, () -> {
                spinning = false;
                services.gambling().revealFlip(viewer, flip);
                refresh();
            });
        });
        if (!spinning) {
            refresh();
        }
    }

    @Override
    public void placeBand(int band, int column, ItemStack item, Consumer<InventoryClickEvent> handler) {
        band(band, column, item, click -> {
            if (!spinning) {
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
        return List.of("Call heads or tails. The coin is flipped", "and paid the moment you call it.");
    }

    @Override
    public String describe() {
        return "a coin flip against the house, with a spinning coin";
    }
}
