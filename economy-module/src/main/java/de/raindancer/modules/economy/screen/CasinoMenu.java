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
        this.bet = new Bet(services, viewer);
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
                "<dark_gray>Blackjack and baccarat: the real casino rules."));
        Money limit = live.dailyLossLimitMoney();
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.CLOCK, "<white>Lost today",
                Mini.of(currency.render(services.gambling().lostToday(viewer.getUniqueId()))),
                limit.isPositive() ? "<gray>of at most " + Mini.of(currency.render(limit)) : "<dark_gray>No daily limit"));

        bet.buttons(this, MenuLayout.WHO, viewer);

        game(MenuLayout.RULES, 1, live.coinflipEnabled(), Material.SUNFLOWER, "Coin flip", "Heads or tails.",
                () -> new CoinFlipMenu(services, viewer, this, bet).open());
        game(MenuLayout.RULES, 2, live.diceEnabled(), Material.WHITE_WOOL, "Dice", "Over or under your number.",
                () -> new DiceMenu(services, viewer, this, bet).open());
        game(MenuLayout.RULES, 3, live.slotsEnabled(), Material.DIAMOND, "Slot machine", "Three reels.",
                () -> new SlotsMenu(services, viewer, this, bet).open());
        game(MenuLayout.RULES, 4, live.rouletteEnabled(), Material.ENDER_PEARL, "Roulette", "Red, black, numbers.",
                () -> new RouletteMenu(services, viewer, this, bet).open());
        game(MenuLayout.RULES, 5, live.blackjackEnabled(), Material.PAPER, "Blackjack", "Beat the dealer to 21.",
                () -> new BlackjackMenu(services, viewer, this, bet).open());
        game(MenuLayout.RULES, 6, live.baccaratEnabled(), Material.RED_CONCRETE, "Baccarat", "Player, banker or tie.",
                () -> new BaccaratMenu(services, viewer, this, bet).open());
        game(MenuLayout.RULES, 7, live.hiloEnabled(), Material.LIME_CONCRETE, "Hi-Lo", "Higher or lower?",
                () -> new HiLoMenu(services, viewer, this, bet).open());
        game(MenuLayout.LAND, 2, live.minesEnabled(), Material.TNT, "Mines", "Clear tiles, avoid mines.",
                () -> new MinesMenu(services, viewer, this, bet).open());
        game(MenuLayout.LAND, 3, live.crashEnabled(), Material.FIREWORK_ROCKET, "Crash", "Cash out before it crashes.",
                () -> CrashMenu.open(services, viewer, this));
        game(MenuLayout.LAND, 4, live.raceEnabled(), Material.SADDLE, "Horse race", "Bet and watch them run.",
                () -> RaceMenu.open(services, viewer, this));
        game(MenuLayout.LAND, 5, live.scratchEnabled(), Material.MAP, "Scratch card",
                "Buy one for " + Mini.of(currency.render(live.scratchPriceMoney())), () -> {
                    services.scratch().buy(viewer, 1);
                    refresh();
                });
        game(MenuLayout.LAND, 6, live.lotteryEnabled(), Material.FILLED_MAP, "Lottery",
                "Pot: " + Mini.of(currency.render(services.lottery().pot())),
                () -> new LotteryMenu(services, viewer, this).open());
    }

    private void game(int band, int column, boolean on, Material icon, String name, String line, Runnable open) {
        band(band, column, services.config().gameOpen(on), Icons.of(icon, "<yellow>" + name, "<gray>" + line), BankMenu.OFF,
                click -> open.run());
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
