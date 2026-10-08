package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.LotteryRule;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Tickets into a pot; one winner per draw, drawn on a timer whether or not anybody is online. */
public final class LotteryService implements IEconomyService {

    private final Server server;
    private final RainEconomy economy;
    private final AccountBook book;
    private final Messages messages;
    private final Effects effects;
    private final LongSupplier clock;
    private final LotteryRule rule = new LotteryRule();
    private final SecureRandom random = new SecureRandom();
    private volatile EconomySettings settings;

    public LotteryService(Server server, RainEconomy economy, Messages messages, Effects effects, LongSupplier clock,
                          EconomySettings settings) {
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

    public Money pot() {
        return book.balance(AccountBook.LOTTERY_POT);
    }

    public Duration untilDraw() {
        return Duration.ofMillis(Math.max(0, book.nextDrawAt() - clock.getAsLong()));
    }

    public void buy(Player player, int wanted) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.gameOpen(live.lotteryEnabled())) {
            refuse(player, "economy.gamble.off");
            return;
        }
        if (!player.hasPermission(PermissionNodes.GAMBLE)) {
            refuse(player, "economy.gamble.not-allowed");
            return;
        }
        int count = rule.allowed(book.ticketsOf(player.getUniqueId()), Math.max(1, wanted), live.mostTickets());
        if (count == 0) {
            refuse(player, "economy.lottery.most", "most", String.valueOf(live.mostTickets()));
            return;
        }
        economy.open(player.getUniqueId(), player.getName());
        EconomyResult result = book.buyTickets(player.getUniqueId(), count, live.ticketPriceMoney(), economy.most());
        if (!result.succeeded()) {
            Outcomes.tell(messages, effects, player, result, currency, "");
            return;
        }
        economy.tell(player.getUniqueId(), result.amount().negate(), result.balance(), TransactionKind.LOTTERY);
        effects.play(player.getUniqueId(), Cues.OK);
        messages.send(player, "economy.lottery.bought", "count", String.valueOf(count),
                "amount", currency.render(result.amount()), "pot", currency.render(pot()),
                "when", Times.describe(untilDraw()));
    }

    public void status(Player player) {
        Currency currency = settings.currency();
        Map<UUID, Integer> tickets = book.tickets();
        long total = rule.total(tickets);
        int mine = tickets.getOrDefault(player.getUniqueId(), 0);
        messages.send(player, "economy.lottery.status", "pot", currency.render(pot()),
                "tickets", String.valueOf(total), "mine", String.valueOf(mine),
                "chance", total == 0 ? "0" : String.format(java.util.Locale.ROOT, "%.1f", 100.0 * mine / total),
                "when", Times.describe(untilDraw()), "price", currency.render(settings.ticketPriceMoney()));
    }

    /** Asked once a minute. */
    public void minute() {
        EconomySettings live = settings;
        if (!live.gameOpen(live.lotteryEnabled()) || !book.isLoaded()) {
            return;
        }
        long now = clock.getAsLong();
        long period = Math.max(1, live.drawHours()) * 3_600_000L;
        if (book.nextDrawAt() <= 0) {
            book.scheduleDraw(now + period);
            return;
        }
        if (now < book.nextDrawAt()) {
            return;
        }
        draw(now + period);
    }

    /** Draws now; staff can force it. */
    public Optional<UUID> draw(long nextAt) {
        Currency currency = settings.currency();
        Map<UUID, Integer> tickets = book.tickets();
        long total = rule.total(tickets);
        if (total == 0) {
            book.settleDraw(null, Money.ZERO, nextAt);
            return Optional.empty();
        }
        Optional<UUID> winner = rule.winner(tickets, random.nextLong(total));
        Money prize = rule.prize(pot(), settings.lotteryCut());
        Money paid = book.settleDraw(winner.orElse(null), prize, nextAt);
        winner.ifPresent(id -> {
            OfflinePlayer who = server.getOfflinePlayer(id);
            String name = who.getName() == null ? "somebody" : who.getName();
            economy.tell(id, paid, economy.balance(id), TransactionKind.LOTTERY);
            for (Player each : server.getOnlinePlayers()) {
                messages.send(each, "economy.lottery.drawn", "player", name, "amount", currency.render(paid),
                        "tickets", String.valueOf(tickets.get(id)), "total", String.valueOf(total));
            }
            Player online = who.getPlayer();
            if (online != null) {
                effects.play(online.getUniqueId(), Cues.REWARD);
            }
        });
        return winner;
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
