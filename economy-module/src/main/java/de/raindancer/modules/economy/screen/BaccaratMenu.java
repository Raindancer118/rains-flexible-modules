package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuAnimation;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.rules.BaccaratRule;
import de.raindancer.modules.economy.service.GameSounds;
import de.raindancer.modules.economy.service.TableService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Baccarat: bet on player, banker or tie; the dealer lays the cards out by the rules, one at a time. */
public final class BaccaratMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;
    private final Bet bet;
    private TableService.Coup coup;
    private int shown;
    private boolean dealing;

    BaccaratMenu(EconomyServices services, Player viewer, Menu parent, Bet bet) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.bet = bet;
    }

    public static void open(EconomyServices services, Player viewer, Menu parent) {
        new BaccaratMenu(services, viewer, parent, new Bet(services, viewer)).open();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Baccarat");
    }

    @Override
    public String breadcrumb() {
        return "Baccarat";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        BaccaratRule rule = services.tables().baccaratRule();
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        String says = coup == null ? "Player, banker or tie?" : dealing ? "…"
                : coup.winner() == BaccaratRule.Side.TIE ? "A tie." : (coup.winner() == BaccaratRule.Side.PLAYER
                ? "Player" : "Banker") + " wins, " + rule.points(coup.player()) + " to " + rule.points(coup.banker()) + ".";
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.PLAYER_HEAD, "<gold>Dealer", "<white>\"" + says + "\""));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.PAPER, "<white>Bet: " + Mini.of(currency.render(bet.amount())),
                "<yellow>Click<gray> to type a bet"), click -> {
            if (!dealing) {
                MoneyPrompt.ask(viewer, "Bet how much?", currency, value -> {
                    bet.set(value);
                    open();
                }, this::open);
            }
        });

        if (coup != null) {
            // Dealt in order: player, banker, player, banker, then any third cards.
            int index = 0;
            int[] order = dealOrder();
            set(1 * 9, Icons.of(Material.BLUE_STAINED_GLASS_PANE, "<blue>Player"));
            set(2 * 9, Icons.of(Material.RED_STAINED_GLASS_PANE, "<red>Banker"));
            for (int step : order) {
                if (index++ >= shown) {
                    break;
                }
                boolean player = step < 10;
                int card = step % 10;
                cell(player ? 1 : 2, card + 2, CardIcons.face((player ? coup.player() : coup.banker()).get(card)),
                        click -> { });
            }
            if (!dealing) {
                set(1 * 9 + 8, Icons.of(Material.OAK_SIGN, "<blue>" + rule.points(coup.player())));
                set(2 * 9 + 8, Icons.of(Material.OAK_SIGN, "<red>" + rule.points(coup.banker())));
            }
        }

        option(2, Material.BLUE_CONCRETE, "<blue>Player", "Pays 1 to 1", BaccaratRule.Side.PLAYER);
        option(4, Material.LIME_CONCRETE, "<green>Tie", "Pays 8 to 1", BaccaratRule.Side.TIE);
        option(6, Material.RED_CONCRETE, "<red>Banker", "Pays 0.95 to 1", BaccaratRule.Side.BANKER);
    }

    private void option(int column, Material icon, String name, String pays, BaccaratRule.Side side) {
        toolbar(column, !dealing, Icons.of(icon, name, "<gray>" + pays, "<yellow>Click<gray> to bet "
                + Mini.of(services.currency().render(bet.amount())) + " <gray>on it"), "The cards are being dealt.",
                click -> play(side));
    }

    /** Player cards as 0..2, banker cards as 10..12, in the order they are dealt. */
    private int[] dealOrder() {
        int[] order = new int[coup.player().size() + coup.banker().size()];
        int at = 0;
        order[at++] = 0;
        order[at++] = 10;
        order[at++] = 1;
        order[at++] = 11;
        if (coup.player().size() > 2) {
            order[at++] = 2;
        }
        if (coup.banker().size() > 2) {
            order[at] = 12;
        }
        return order;
    }

    private void play(BaccaratRule.Side side) {
        if (dealing) {
            return;
        }
        services.tables().baccarat(viewer, bet.amount(), side).ifPresent(dealt -> {
            coup = dealt;
            shown = 0;
            dealing = true;
            int cards = dealt.player().size() + dealt.banker().size();
            MenuAnimation.play(services.plugin(), this, MenuAnimation.schedule(cards, 10, 14), frame -> {
                shown++;
                services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.CARD);
                refresh();
            }, () -> {
                shown = cards;
                dealing = false;
                services.tables().revealCoup(viewer, dealt);
                refresh();
            });
        });
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Bet on the hand that ends closer to 9.", "Tens and faces count 0; a hand is its last digit.",
                "The third cards follow the casino's fixed rules.");
    }

    @Override
    public String describe() {
        return "baccarat with the dealer, cards laid out by the rules";
    }
}
