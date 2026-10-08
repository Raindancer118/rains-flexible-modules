package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.LotteryTicket;
import de.raindancer.modules.economy.service.GameSounds;
import de.raindancer.modules.economy.service.LotteryService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The lottery desk: a board of numbers to tick, the pot, your tickets and the last draw. Tick as many
 * numbers as a ticket holds, or let quick pick choose.
 */
public final class LotteryMenu extends Menu implements IEconomyScreen {

    /** The most numbers the board shows; a bigger lottery is played with quick picks or typed numbers. */
    private static final int BOARD = 36;

    private final EconomyServices services;
    private final TreeSet<Integer> ticked = new TreeSet<>();

    public LotteryMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Lottery");
    }

    @Override
    public String breadcrumb() {
        return "Lottery";
    }

    @Override
    protected void render() {
        LotteryService lottery = services.lottery();
        Currency currency = services.currency();
        int pick = lottery.pick();
        int range = lottery.range();
        List<Integer> last = lottery.lastDraw();
        for (int number = 1; number <= Math.min(range, BOARD); number++) {
            int value = number;
            boolean on = ticked.contains(number);
            boolean drawn = last.contains(number);
            ItemStack ball = Icons.of(on ? Material.LIME_CONCRETE : drawn ? Material.YELLOW_CONCRETE : Material.WHITE_CONCRETE,
                    (on ? "<green><bold>" : "<white>") + number,
                    drawn ? "<yellow>Drawn last time" : "",
                    on ? "<yellow>Click<gray> to untick" : "<yellow>Click<gray> to tick");
            ball.setAmount(number);
            set(number - 1, ball, click -> {
                if (!ticked.remove(value) && ticked.size() < pick) {
                    ticked.add(value);
                }
                services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.CHIPS);
                refresh();
            });
        }

        List<LotteryTicket> mine = lottery.ticketsOf(viewer.getUniqueId());
        List<String> lines = new ArrayList<>();
        mine.stream().limit(12).forEach(ticket -> lines.add("<white>" + ticket.written()));
        if (mine.size() > 12) {
            lines.add("<gray>and " + (mine.size() - 12) + " more");
        }
        toolbar(1, Icons.of(Material.GOLD_BLOCK, "<gold>Pot: " + Mini.of(currency.render(lottery.pot())),
                "<gray>Draw in " + Times.describe(lottery.untilDraw()),
                "<gray>All " + pick + " right: 60% of the pot", "<gray>One short: 25%  ·  two short: 15%",
                "<dark_gray>What nobody wins stays in the pot.",
                "<dark_gray>Jackpot odds: 1 in " + Math.round(1 / lottery.rule().chance(pick, pick, range))), click -> { });
        toolbar(3, Icons.of(Material.ENDER_EYE, "<aqua>Quick pick", "<gray>Let luck tick " + pick + " numbers."), click -> {
            ticked.clear();
            ticked.addAll(lottery.quickPick());
            services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.CHIPS);
            refresh();
        });
        toolbar(4, ticked.size() == pick && !lottery.drawing(), Icons.of(Material.FILLED_MAP, "<green>Buy this ticket",
                        "<white>" + String.join(" ", ticked.stream().map(String::valueOf).toList()),
                        "<gray>for " + Mini.of(currency.render(services.config().ticketPriceMoney())),
                        "<yellow>Shift click<gray> for five of them"),
                lottery.drawing() ? "The draw is being called." : "Tick " + pick + " numbers first.", click -> {
                    if (lottery.buy(viewer, List.copyOf(ticked), click.isShiftClick() ? 5 : 1)) {
                        ticked.clear();
                    }
                    refresh();
                });
        toolbar(5, Icons.of(Material.PAPER, "<white>Your tickets: " + mine.size(), lines), click -> { });
        toolbar(7, Icons.of(Material.BELL, "<white>Last draw",
                last.isEmpty() ? "<gray>None yet" : "<yellow>" + String.join(" ", last.stream().map(String::valueOf).toList())), click -> { });
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Tick " + services.lottery().pick() + " numbers and buy a ticket.",
                "At the draw the same number of balls come out.", "The more you got right, the bigger your share.");
    }

    @Override
    public String describe() {
        return "the lottery desk";
    }
}
