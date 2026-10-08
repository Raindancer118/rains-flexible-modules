package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /casino}: pick a bet, pick a game. The bet follows you into whichever game you open. */
public final class CasinoMenu extends Menu implements IEconomyScreen, Bet.BetMenu {

    private final EconomyServices services;
    private final Bet bet;

    public CasinoMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.bet = new Bet(services);
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
        EconomySettings live = services.config();
        Currency currency = services.currency();
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GOLD_BLOCK, "<gold>Casino",
                "<gray>The house keeps " + String.format("%.1f", live.houseEdge() * 100) + "% on average.",
                "<dark_gray>That is the exact edge, not an estimate."));
        Money limit = live.dailyLossLimitMoney();
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.CLOCK, "<white>Lost today",
                Mini.of(currency.render(services.gambling().lostToday(viewer.getUniqueId()))),
                limit.isPositive() ? "<gray>of at most " + Mini.of(currency.render(limit)) : "<dark_gray>No daily limit"));

        bet.buttons(this, MenuLayout.WHO, viewer);

        band(MenuLayout.RULES, 2, live.gameOpen(live.coinflipEnabled()), Icons.of(Material.SUNFLOWER,
                "<yellow>Coin flip", "<gray>Heads or tails. Win: " + Mini.of(currency.render(
                        services.gambling().flipWouldPay(bet.amount())))), BankMenu.OFF,
                click -> new CoinFlipMenu(services, viewer, this, bet).open());
        band(MenuLayout.RULES, 4, live.gameOpen(live.diceEnabled()), Icons.of(Material.WHITE_WOOL,
                "<yellow>Dice", "<gray>Roll 1 to 100, over or under your number.",
                "<gray>Long odds pay more."), BankMenu.OFF,
                click -> new DiceMenu(services, viewer, this, bet).open());
        band(MenuLayout.RULES, 6, live.gameOpen(live.slotsEnabled()), Icons.of(Material.DIAMOND,
                "<yellow>Slot machine", "<gray>Three reels. Netherite is wild money."), BankMenu.OFF,
                click -> new SlotsMenu(services, viewer, this, bet).open());

        band(MenuLayout.LAND, 1, live.gameOpen(live.rouletteEnabled()), Icons.of(Material.ENDER_PEARL,
                "<yellow>Roulette", "<gray>Red, black, green, numbers, dozens."), BankMenu.OFF,
                click -> new RouletteMenu(services, viewer, this, bet).open());

        boolean lottery = live.gameOpen(live.lotteryEnabled());
        band(MenuLayout.LAND, 4, lottery, Icons.of(Material.FILLED_MAP, "<aqua>Lottery ticket",
                "<gray>One for " + Mini.of(currency.render(live.ticketPriceMoney())),
                "<gray>Pot: " + Mini.of(currency.render(services.lottery().pot())),
                "<gray>You hold " + services.economy().book().ticketsOf(viewer.getUniqueId()) + " ticket(s)",
                "", "<yellow>Click<gray> for one, <yellow>shift click<gray> for ten"), BankMenu.OFF, click -> {
            services.lottery().buy(viewer, click.isShiftClick() ? 10 : 1);
            refresh();
        });
        band(MenuLayout.LAND, 6, lottery, Icons.of(Material.CLOCK, "<aqua>Next draw",
                "<gray>In " + de.raindancer.core.world.time.Times.describe(services.lottery().untilDraw())),
                BankMenu.OFF, click -> services.lottery().status(viewer));
    }

    @Override
    public void placeBand(int band, int column, org.bukkit.inventory.ItemStack item,
                          java.util.function.Consumer<org.bukkit.event.inventory.InventoryClickEvent> handler) {
        band(band, column, item, handler);
    }

    @Override
    public void reopenAfterPrompt() {
        open();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Set your bet in the top row; every game uses it.",
                "Each game is decided and paid the moment you play;",
                "the spinning is only the show.");
    }

    @Override
    public String describe() {
        return "the casino: bets and games";
    }
}
