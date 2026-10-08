package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuAnimation;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Card;
import de.raindancer.modules.economy.rules.BlackjackRule;
import de.raindancer.modules.economy.service.GameSounds;
import de.raindancer.modules.economy.service.TableService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Blackjack at the dealer's table. The dealer's cards along the top — the second face down until the
 * player is done — the player's hand (or two, after a split) below. Cards are laid down one at a time.
 */
public final class BlackjackMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;
    private final Bet bet;
    private int shownDealer;
    private int shownPlayer;
    private boolean dealing;

    BlackjackMenu(EconomyServices services, Player viewer, Menu parent, Bet bet) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.bet = bet;
        services.tables().blackjack(viewer.getUniqueId()).ifPresent(game -> {
            shownDealer = game.dealer.size();
            shownPlayer = game.hands.stream().mapToInt(List::size).sum();
        });
    }

    public static void open(EconomyServices services, Player viewer, Menu parent) {
        new BlackjackMenu(services, viewer, parent, new Bet(services)).open();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Blackjack");
    }

    @Override
    public String breadcrumb() {
        return "Blackjack";
    }

    private Optional<TableService.Blackjack> game() {
        return services.tables().blackjack(viewer.getUniqueId());
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        BlackjackRule rule = services.tables().blackjackRule();
        Optional<TableService.Blackjack> current = game();
        boolean playing = current.isPresent() && !current.get().finished;

        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.PLAYER_HEAD, "<gold>Dealer",
                "<white>\"" + current.map(game -> dealing ? "…" : game.says).orElse("Place your bet.") + "\""));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.PAPER, "<white>Bet: " + Mini.of(currency.render(bet.amount())),
                playing ? "<dark_gray>In play" : "<yellow>Click<gray> to type a bet"), click -> {
            if (!playing && !dealing) {
                MoneyPrompt.ask(viewer, "Bet how much?", currency, value -> {
                    bet.set(value);
                    open();
                }, this::open);
            }
        });

        current.ifPresent(game -> {
            boolean hidden = !game.finished || dealing && shownDealer < game.dealer.size();
            for (int i = 0; i < game.dealer.size() && i < 7; i++) {
                if (i >= shownDealer) {
                    break;
                }
                boolean faceDown = i == 1 && !game.finished;
                cell(1, i + 1, faceDown ? CardIcons.back() : CardIcons.face(game.dealer.get(i)), click -> { });
            }
            if (!hidden || game.finished) {
                set(1 * 9 + 8, Icons.of(Material.OAK_SIGN, "<gray>Dealer: <white>"
                        + rule.total(game.dealer.subList(0, Math.min(shownDealer, game.dealer.size())))));
            }
            int dealt = 0;
            for (int hand = 0; hand < game.hands.size(); hand++) {
                List<Card> cards = game.hands.get(hand);
                for (int i = 0; i < cards.size() && i < 7; i++) {
                    if (dealt++ >= shownPlayer) {
                        break;
                    }
                    cell(2 + hand, i + 1, CardIcons.face(cards.get(i)), click -> { });
                }
                boolean active = !game.finished && hand == game.active;
                set((2 + hand) * 9, Icons.of(active ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                        (active ? "<green>▶ " : "<gray>") + "Your hand" + (game.hands.size() > 1 ? " " + (hand + 1) : ""),
                        "<gray>Bet " + Mini.of(currency.render(game.stakes.get(hand)))));
                set((2 + hand) * 9 + 8, Icons.of(Material.OAK_SIGN, "<gray>You: <white>" + rule.total(cards)));
            }
        });

        if (playing && !dealing) {
            TableService.Blackjack game = current.get();
            List<Card> hand = game.hands.get(game.active);
            toolbar(1, Icons.of(Material.LIME_CONCRETE, "<green>Hit", "<gray>One more card."), click -> act(() ->
                    services.tables().hit(viewer)));
            toolbar(3, Icons.of(Material.RED_CONCRETE, "<red>Stand", "<gray>Keep what you have."), click -> act(() ->
                    services.tables().stand(viewer)));
            toolbar(5, rule.canDouble(hand), Icons.of(Material.GOLD_BLOCK, "<gold>Double",
                    "<gray>Double the bet, take exactly one card."), "Only on your first two cards.",
                    click -> act(() -> services.tables().doubleDown(viewer)));
            toolbar(7, !game.split && rule.canSplit(game.hands.getFirst()), Icons.of(Material.SHEARS, "<aqua>Split",
                    "<gray>Two hands, a bet on each."), "Only a pair, and only once.",
                    click -> act(() -> services.tables().split(viewer)));
        } else if (!dealing) {
            toolbar(4, Icons.of(Material.EMERALD, "<green>Deal",
                    "<gray>" + Mini.of(currency.render(bet.amount())) + " <gray>on the next hand"), click -> act(() ->
                    services.tables().deal(viewer, bet.amount())));
        }
    }

    /** Does what was clicked, then lays down whatever new cards it brought, one at a time. */
    private void act(Runnable action) {
        if (dealing) {
            return;
        }
        action.run();
        Optional<TableService.Blackjack> current = game();
        if (current.isEmpty()) {
            refresh();
            return;
        }
        TableService.Blackjack game = current.get();
        if (game.hands.stream().mapToInt(List::size).sum() < shownPlayer || game.dealer.size() < shownDealer) {
            shownPlayer = 0;
            shownDealer = 0;
        }
        int playerCards = game.hands.stream().mapToInt(List::size).sum();
        int toDeal = Math.max(0, playerCards - shownPlayer) + Math.max(0, game.dealer.size() - shownDealer);
        if (toDeal == 0) {
            finishIfDone(game);
            refresh();
            return;
        }
        dealing = true;
        MenuAnimation.play(services.plugin(), this, MenuAnimation.schedule(toDeal, 7, 9), frame -> {
            // Alternate as a dealer does: the player's card, then the dealer's.
            boolean playerNext = shownPlayer < playerCards
                    && (shownDealer >= game.dealer.size() || shownPlayer <= shownDealer);
            if (playerNext) {
                shownPlayer++;
            } else {
                shownDealer++;
            }
            services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.CARD);
            refresh();
        }, () -> {
            shownPlayer = playerCards;
            shownDealer = game.dealer.size();
            dealing = false;
            finishIfDone(game);
            refresh();
        });
    }

    private void finishIfDone(TableService.Blackjack game) {
        if (game.finished) {
            services.tables().tell(viewer, game);
        }
    }

    @Override
    public void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        super.handleClose(event);
        services.tables().blackjack(viewer.getUniqueId()).ifPresent(game -> {
            if (game.finished) {
                services.tables().tell(viewer, game);
            }
        });
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Get closer to 21 than the dealer without going over.",
                "Aces count 1 or 11, faces 10. The dealer stands on 17.",
                "A blackjack — 21 on two cards — pays 3 to 2.");
    }

    @Override
    public String describe() {
        return "blackjack against the dealer, cards laid down one by one";
    }
}
