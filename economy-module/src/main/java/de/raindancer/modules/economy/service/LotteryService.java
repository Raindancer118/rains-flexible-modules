package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.LotteryTicket;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.LotteryRule;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The lottery: tickets with numbers, a pot that grows with every ticket and every draw nobody wins, and a
 * draw on a timer, called out ball by ball to everybody online.
 */
public final class LotteryService implements IEconomyService {

    /** Ticks between two balls when a draw is called out. */
    private static final long BALL_TICKS = 50L;

    private final Plugin plugin;
    private final Server server;
    private final RainEconomy economy;
    private final AccountBook book;
    private final Messages messages;
    private final Effects effects;
    private final LongSupplier clock;
    private final LotteryRule rule = new LotteryRule();
    private final SecureRandom random = new SecureRandom();
    private volatile EconomySettings settings;
    private volatile List<Integer> lastDraw = List.of();
    private volatile boolean drawing;

    public LotteryService(Plugin plugin, Server server, RainEconomy economy, Messages messages, Effects effects,
                          LongSupplier clock, EconomySettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.economy = economy;
        this.book = economy.book();
        this.messages = messages;
        this.effects = effects;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public LotteryRule rule() {
        return rule;
    }

    public int pick() {
        return settings.lotteryPick();
    }

    public int range() {
        return settings.lotteryNumbers();
    }

    public Money pot() {
        return book.balance(AccountBook.LOTTERY_POT);
    }

    public List<Integer> lastDraw() {
        return lastDraw;
    }

    public boolean drawing() {
        return drawing;
    }

    public Duration untilDraw() {
        return Duration.ofMillis(Math.max(0, book.nextDrawAt() - clock.getAsLong()));
    }

    public List<LotteryTicket> ticketsOf(UUID player) {
        return book.ticketsOf(player);
    }

    public List<Integer> quickPick() {
        return rule.draw(random, pick(), range());
    }

    /**
     * Buys tickets. With numbers, that many tickets with those numbers; without, quick picks.
     *
     * @return whether anything was bought
     */
    public boolean buy(Player player, List<Integer> numbers, int wanted) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.gameOpen(live.lotteryEnabled())) {
            refuse(player, "economy.gamble.off");
            return false;
        }
        if (!player.hasPermission(PermissionNodes.GAMBLE)) {
            refuse(player, "economy.gamble.not-allowed");
            return false;
        }
        if (drawing) {
            refuse(player, "economy.lottery.drawing");
            return false;
        }
        if (numbers != null && !rule.valid(numbers, pick(), range())) {
            refuse(player, "economy.lottery.pick", "pick", String.valueOf(pick()), "range", String.valueOf(range()));
            return false;
        }
        int count = rule.allowed(book.ticketsOf(player.getUniqueId()).size(), Math.max(1, wanted), live.mostTickets());
        if (count == 0) {
            refuse(player, "economy.lottery.most", "most", String.valueOf(live.mostTickets()));
            return false;
        }
        List<List<Integer>> picks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            picks.add(numbers != null ? numbers : quickPick());
        }
        economy.open(player.getUniqueId(), player.getName());
        Money price = live.ticketPriceMoney();
        EconomyResult result = book.buyTickets(player.getUniqueId(), picks, price,
                price.minus(rule.afterCut(price, live.lotteryCut())), economy.most());
        if (!result.succeeded()) {
            Outcomes.tell(messages, effects, player, result, currency, "");
            return false;
        }
        economy.tell(player.getUniqueId(), result.amount().negate(), result.balance(), TransactionKind.LOTTERY);
        effects.play(player.getUniqueId(), Cues.OK);
        messages.send(player, "economy.lottery.bought", "count", String.valueOf(count),
                "numbers", picks.size() == 1 ? new LotteryTicket(player.getUniqueId(), picks.getFirst()).written() : "quick picks",
                "amount", currency.render(result.amount()), "pot", currency.render(pot()), "when", Times.describe(untilDraw()));
        return true;
    }

    public void status(Player player) {
        Currency currency = settings.currency();
        List<LotteryTicket> mine = book.ticketsOf(player.getUniqueId());
        messages.send(player, "economy.lottery.status", "pot", currency.render(pot()),
                "tickets", String.valueOf(book.tickets().size()), "mine", String.valueOf(mine.size()),
                "when", Times.describe(untilDraw()), "price", currency.render(settings.ticketPriceMoney()),
                "pick", String.valueOf(pick()), "range", String.valueOf(range()));
    }

    /** Asked once a minute. */
    public void minute() {
        EconomySettings live = settings;
        if (!live.gameOpen(live.lotteryEnabled()) || !book.isLoaded() || drawing) {
            return;
        }
        long now = clock.getAsLong();
        long period = Math.max(1, live.drawHours()) * 3_600_000L;
        if (book.nextDrawAt() <= 0) {
            book.scheduleDraw(now + period);
            return;
        }
        if (now >= book.nextDrawAt()) {
            draw(now + period);
        }
    }

    /**
     * Draws now: the result is settled at once and then called out ball by ball, so nothing a viewer does —
     * and no restart halfway through the calling — changes who won.
     */
    public void draw(long nextAt) {
        if (drawing) {
            return;
        }
        Currency currency = settings.currency();
        List<Integer> balls = rule.draw(random, pick(), range());
        List<LotteryTicket> tickets = book.tickets();
        Money potBefore = pot();
        Map<UUID, Money> prizes = rule.prizes(tickets, balls, potBefore, pick());
        book.settleDraw(prizes, nextAt);
        lastDraw = balls;
        drawing = true;
        broadcast("economy.lottery.drawing-now", "pot", currency.render(potBefore),
                "tickets", String.valueOf(tickets.size()));
        for (int i = 0; i < balls.size(); i++) {
            int index = i;
            Scheduling.globalLater(plugin, BALL_TICKS * (i + 1), () -> {
                broadcast("economy.lottery.ball", "number", String.valueOf(balls.get(index)),
                        "which", String.valueOf(index + 1), "of", String.valueOf(balls.size()));
                server.getOnlinePlayers().forEach(player -> effects.play(player.getUniqueId(), GameSounds.BELL));
            });
        }
        Scheduling.globalLater(plugin, BALL_TICKS * (balls.size() + 1), () -> {
            drawing = false;
            String numbers = new LotteryTicket(new UUID(0, 0), balls).written();
            if (prizes.isEmpty()) {
                broadcast("economy.lottery.nobody", "numbers", numbers, "pot", currency.render(pot()));
                return;
            }
            prizes.forEach((id, prize) -> {
                OfflinePlayer who = server.getOfflinePlayer(id);
                economy.tell(id, prize, economy.balance(id), TransactionKind.LOTTERY);
                broadcast("economy.lottery.won", "player", who.getName() == null ? "somebody" : who.getName(),
                        "amount", currency.render(prize), "numbers", numbers);
                Player online = who.getPlayer();
                if (online != null) {
                    effects.play(online.getUniqueId(), GameSounds.JACKPOT);
                }
            });
            broadcast("economy.lottery.next", "pot", currency.render(pot()));
        });
    }

    private void broadcast(String key, Object... values) {
        server.getOnlinePlayers().forEach(player -> messages.send(player, key, values));
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
