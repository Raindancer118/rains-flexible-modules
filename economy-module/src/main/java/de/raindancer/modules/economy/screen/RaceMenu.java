package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuAnimation;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.rules.HorseRaceRule;
import de.raindancer.modules.economy.service.RaceService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The server's horse race, live: four horses on four lanes running left to right across the window. Bet on a
 * horse while the gate is closed, then watch.
 */
public final class RaceMenu extends Menu implements IEconomyScreen {

    private static final Material[] COLOURS = {Material.RED_WOOL, Material.BLUE_WOOL, Material.YELLOW_WOOL,
            Material.LIME_WOOL, Material.PURPLE_WOOL, Material.ORANGE_WOOL};

    private final EconomyServices services;
    private final Bet bet;

    RaceMenu(EconomyServices services, Player viewer, Menu parent, Bet bet) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.bet = bet;
    }

    public static void open(EconomyServices services, Player viewer, Menu parent) {
        RaceMenu menu = new RaceMenu(services, viewer, parent, new Bet(services, viewer));
        menu.open();
        MenuAnimation.loop(services.plugin(), menu, 8L, menu::refresh, () -> { });
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Horse race");
    }

    @Override
    public String breadcrumb() {
        return "Horse race";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        RaceService race = services.race();
        RaceService.Phase phase = race.phase();
        int[] at = race.positions();
        int winner = race.winner();
        for (int horse = 0; horse < race.rule().horses(); horse++) {
            lane(horse, horse, at, winner, phase, currency);
        }
        if (phase == RaceService.Phase.BETTING) {
            toolbar(1, Icons.of(Material.PAPER, "<white>Bet: " + Mini.of(currency.render(bet.amount())),
                    "<yellow>Click<gray> to type a bet"), click -> MoneyPrompt.ask(viewer, "Bet how much?", currency,
                    value -> {
                        bet.set(value);
                        open(services, viewer, null);
                    }, () -> open(services, viewer, null)));
            toolbar(4, Icons.of(Material.CLOCK, "<yellow>Gate opens in " + race.secondsToStart() + " s",
                    "<gray>Click a horse to bet on it."), click -> { });
        } else {
            toolbar(4, Icons.of(phase == RaceService.Phase.RUNNING ? Material.SADDLE : Material.GOLD_BLOCK,
                    phase == RaceService.Phase.RUNNING ? "<yellow>They're off!"
                            : "<gold>" + HorseRaceRule.NAMES.get(Math.max(0, winner)) + " wins!"), click -> { });
        }
        List<RaceService.Bet> mine = race.betsOf(viewer.getUniqueId());
        if (!mine.isEmpty()) {
            StringBuilder lines = new StringBuilder();
            mine.forEach(one -> lines.append(HorseRaceRule.NAMES.get(one.horse())).append(" "));
            toolbar(7, Icons.of(Material.NAME_TAG, "<white>Your bets", "<gray>" + lines.toString().trim()), click -> { });
        }
    }

    /** One horse's lane: the name at the start, the horse along the track, the finish at the end. */
    private void lane(int horse, int row, int[] at, int winner, RaceService.Phase phase, Currency currency) {
        set(row * 9, Icons.of(COLOURS[horse], "<white>" + HorseRaceRule.NAMES.get(horse),
                "<gray>Pays " + String.format("%.2f", services.race().pays(horse)) + "×",
                phase == RaceService.Phase.BETTING ? "<yellow>Click<gray> to bet " + Mini.of(currency.render(bet.amount()))
                        : ""), click -> betOn(horse));
        int column = (int) Math.round(at[horse] / (double) HorseRaceRule.TRACK * 6);
        int slot = row * 9 + 1 + column;
        String name = (horse == winner ? "<gold>★ " : "<white>") + HorseRaceRule.NAMES.get(horse);
        set(slot, Icons.of(Material.LEAD, name, "<gray>Pays " + String.format("%.2f", services.race().pays(horse)) + "×",
                phase == RaceService.Phase.BETTING ? "<yellow>Click<gray> to bet on it" : ""), click -> betOn(horse));
        set(row * 9 + 8, Icons.of(Material.WHITE_BANNER, "<white>Finish"));
    }

    private void betOn(int horse) {
        if (services.race().phase() == RaceService.Phase.BETTING) {
            services.race().bet(viewer, horse, bet.amount());
            refresh();
        }
    }

    @Override
    protected List<String> helpLines() {
        return List.of("One race for everybody. Bet on a horse while", "the gate is closed — as often as you like —",
                "then watch them run. Long shots pay more.");
    }

    @Override
    public String describe() {
        return "the server's horse race, live";
    }
}
