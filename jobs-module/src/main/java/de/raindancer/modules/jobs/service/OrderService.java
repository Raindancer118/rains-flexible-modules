package de.raindancer.modules.jobs.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.jobs.OrderSettings;
import de.raindancer.modules.jobs.model.Order;
import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.model.Work;
import de.raindancer.modules.jobs.rules.OrderRule;
import de.raindancer.modules.jobs.store.OrderBook;
import de.raindancer.modules.jobs.store.WorkCatalogue;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Orders: a player names what they want to earn and gets work to match — harder, more and faster the more they
 * ask — which pays exactly that if done in time, and nothing if not.
 */
public final class OrderService {

    private static final String SOURCE = "jobs.order";

    /** Work offered for an amount, not yet taken. */
    public record Offer(Work work, int units, Duration time, Money pay, String says) {
    }

    /** Offers to pick from, or why there are none: a message key, with the amount that matters to it. */
    public record Answer(java.util.List<Offer> offers, String refusal, Money amount) {

        public Answer {
            offers = offers == null ? java.util.List.of() : java.util.List.copyOf(offers);
        }

        static Answer no(String key, Money amount) {
            return new Answer(null, key, amount);
        }

        /** The first offer, or null when refused. */
        public Offer offer() {
            return offers.isEmpty() ? null : offers.getFirst();
        }
    }

    private final Server server;
    private final WorkCatalogue works;
    private final OrderBook book;
    private final Messages messages;
    private final LogChannel log;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final Random random;
    private final OrderRule rule = new OrderRule();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    /** The offers each player was last made, so only one of those can be taken. */
    private final Map<UUID, java.util.List<Offer>> offered = new ConcurrentHashMap<>();
    private volatile OrderSettings settings;

    public OrderService(Server server, WorkCatalogue works, OrderBook book, Messages messages, LogChannel log,
                        LongSupplier clock, ZoneId zone, Random random, OrderSettings settings) {
        this.server = server;
        this.works = works;
        this.book = book;
        this.messages = messages;
        this.log = log;
        this.clock = clock;
        this.zone = zone;
        this.random = random;
        settings(settings);
    }

    public void settings(OrderSettings updated) {
        this.settings = updated == null ? OrderSettings.DEFAULTS : updated;
    }

    public OrderSettings settings() {
        return settings;
    }

    public int reload() {
        return works.reload();
    }

    public Optional<Work> work(Order order) {
        return works.find(order.work());
    }

    /** Every block some work could ask to be mined — for Core's PlacedBlocks. */
    public Set<Material> minedBlocks() {
        Set<Material> blocks = EnumSet.noneOf(Material.class);
        for (Work work : works.all()) {
            if (work.task() != QuestTask.MINE) {
                continue;
            }
            for (Material material : Material.values()) {
                if (!material.isLegacy() && material.isBlock() && work.things().covers(material.name())) {
                    blocks.add(material);
                }
            }
        }
        return blocks;
    }

    private String today() {
        return Instant.ofEpochMilli(clock.getAsLong()).atZone(zone).toLocalDate().toString();
    }

    /** Today's ledger: counts start again on a new day, an order running over midnight is kept. */
    private OrderBook.Ledger ledger(UUID player) {
        String today = today();
        return book.of(player).map(held -> held.day().equals(today) ? held
                : new OrderBook.Ledger(today, 0, 0, false, held.order())).orElse(new OrderBook.Ledger(today, 0, 0, false, null));
    }

    public Optional<Order> current(UUID player) {
        return book.of(player).flatMap(OrderBook.Ledger::current);
    }

    private static boolean running(Order order) {
        return order.state() == Order.State.OPEN;
    }

    /**
     * What {@code asked} would be: an offer, or why not. Asking while an offer is still open is a re-roll, and
     * counts against the day's.
     */
    public Answer ask(UUID player, Money asked) {
        return ask(player, asked, null);
    }

    /**
     * The same with a time the player chose: work that fits it, at the order's pace, never less than the amount
     * buys. Null lets the work decide the time.
     */
    /**
     * Why {@code asked} cannot be an order for this player right now — before anything is offered, so a screen
     * asking how long they have is not shown for an amount that would be refused anyway.
     */
    public synchronized Optional<Answer> refusal(UUID player, Money asked) {
        OrderSettings live = settings;
        Money least = Fees.amount(live.least());
        Money most = Fees.amount(live.hardestAt());
        if (!live.enabled()) {
            return Optional.of(Answer.no("jobs.order.switched-off", asked));
        }
        if (least.isMoreThan(asked)) {
            return Optional.of(Answer.no("jobs.order.too-little", least));
        }
        if (most.isPositive() && asked.isMoreThan(most)) {
            return Optional.of(Answer.no("jobs.order.too-much", most));
        }
        OrderBook.Ledger ledger = ledger(player);
        if (ledger.current().filter(OrderService::running).isPresent()) {
            return Optional.of(Answer.no("jobs.order.busy", asked));
        }
        if (ledger.taken() >= live.perDay()) {
            return Optional.of(Answer.no("jobs.order.none-left", asked));
        }
        return Optional.empty();
    }

    public synchronized Answer ask(UUID player, Money asked, Duration chosen) {
        Optional<Answer> refused = refusal(player, asked);
        if (refused.isPresent()) {
            return refused.get();
        }
        OrderSettings live = settings;
        Money most = Fees.amount(live.hardestAt());
        OrderBook.Ledger ledger = ledger(player);
        boolean reroll = ledger.offered();
        if (reroll && ledger.rerolls() >= live.rerollsPerDay()) {
            return Answer.no("jobs.order.no-rerolls", asked);
        }
        if (works.all().isEmpty()) {
            return Answer.no("jobs.order.no-work", asked);
        }
        OrderBook.Ledger next = new OrderBook.Ledger(ledger.day(), ledger.taken(),
                ledger.rerolls() + (reroll ? 1 : 0), true, ledger.order());
        if (!book.putNow(player, next)) {
            return Answer.no("jobs.order.not-saved", asked);
        }
        double difficulty = rule.difficulty(asked, Fees.amount(live.easyUpTo()), most);
        double pace = rule.pressure(difficulty, live.paceEasiest(), live.paceHardest());
        java.util.List<Offer> offers = new java.util.ArrayList<>();
        if (chosen == null) {
            for (Work work : rule.pickSome(works.all(), difficulty, live.choices(), asked, random)) {
                int units = rule.units(asked, rule.perUnit(Fees.amount(work.value()), difficulty),
                        0.8 + random.nextDouble() * 0.45);
                offers.add(new Offer(work, units, rule.time(units, work.rate(), pace), asked, work.says(units)));
            }
        } else {
            Duration time = Duration.ofMinutes(Math.clamp(chosen.toMinutes(), OrderRule.SHORTEST.toMinutes(),
                    OrderRule.LONGEST.toMinutes()));
            for (Work work : rule.fitting(works.all(), difficulty, asked, time, pace, live.choices(), random)) {
                int units = rule.unitsIn(asked, work, difficulty, time, pace);
                offers.add(new Offer(work, units, time, asked, work.says(units)));
            }
        }
        if (offers.isEmpty()) {
            return Answer.no("jobs.order.no-work", asked);
        }
        offered.put(player, java.util.List.copyOf(offers));
        return new Answer(offers, null, asked);
    }

    /** Re-rolls left today. */
    public int rerollsLeft(UUID player) {
        return Math.max(0, settings.rerollsPerDay() - ledger(player).rerolls());
    }

    /** Takes an offer: the clock starts now. */
    public synchronized boolean accept(Player player, Offer offer) {
        UUID id = player.getUniqueId();
        OrderBook.Ledger ledger = ledger(id);
        if (ledger.current().filter(OrderService::running).isPresent()) {
            messages.send(player, "jobs.order.busy");
            return false;
        }
        if (ledger.taken() >= settings.perDay()) {
            messages.send(player, "jobs.order.none-left");
            return false;
        }
        // Only one of the offers last made, and only once: an old screen cannot take work turned down or done.
        if (!ledger.offered() || offered.getOrDefault(id, java.util.List.of()).stream().noneMatch(each -> each == offer)) {
            messages.send(player, "jobs.order.stale");
            return false;
        }
        long now = clock.getAsLong();
        Order order = new Order(offer.work().id(), offer.says(), offer.units(), offer.pay(), now,
                now + offer.time().toMillis(), 0, Order.State.OPEN);
        if (!book.putNow(id, new OrderBook.Ledger(ledger.day(), ledger.taken() + 1, ledger.rerolls(), false, order))) {
            messages.send(player, "jobs.order.not-saved");
            return false;
        }
        offered.remove(id);
        messages.send(player, "jobs.order.go", "work", offer.says(), "time", Times.describe(offer.time()),
                "amount", Fees.format(offer.pay()));
        return true;
    }

    /** Gives up the running order. It still counts as one of the day's. */
    public synchronized boolean cancel(Player player) {
        UUID id = player.getUniqueId();
        OrderBook.Ledger ledger = ledger(id);
        if (ledger.current().filter(OrderService::running).isEmpty()) {
            return false;
        }
        if (!book.putNow(id, ledger.with(ledger.order().in(Order.State.FAILED)))) {
            return false;
        }
        hide(player);
        return true;
    }

    /** Something done that may count towards the running order. */
    public synchronized void progress(Player player, QuestTask task, String thing, int count) {
        UUID id = player.getUniqueId();
        Optional<OrderBook.Ledger> held = book.of(id);
        if (count <= 0 || held.isEmpty() || held.get().current().filter(OrderService::running).isEmpty()) {
            return;
        }
        Order order = held.get().order();
        if (clock.getAsLong() >= order.endsAt()) {
            return;
        }
        Optional<Work> work = works.find(order.work());
        if (work.isEmpty() || work.get().task() != task || task != QuestTask.TRAVEL && !work.get().things().covers(thing)) {
            return;
        }
        Order moved = order.plus(count);
        if (!moved.done()) {
            book.put(id, held.get().with(moved));
            return;
        }
        OrderBook.Ledger paid = held.get().with(moved.in(Order.State.PAID));
        if (!book.putNow(id, paid)) {
            book.put(id, held.get().with(moved));
            messages.send(player, "jobs.order.not-saved");
            return;
        }
        hide(player);
        EconomyResult result = Fees.pay(id, order.pay(), "Order: " + order.says(), SOURCE);
        if (result.succeeded()) {
            messages.send(player, "jobs.order.done", "work", order.says(), "amount", Fees.format(result.amount()),
                    "left", Times.describe(Duration.ofMillis(order.endsAt() - clock.getAsLong())));
            return;
        }
        if (!book.putNow(id, paid.with(moved.in(Order.State.OWED)))) {
            book.put(id, paid.with(moved.in(Order.State.OWED)));
        }
        log.warn("{} could not be paid for an order: {} — kept as owed.", player.getName(), result.outcome());
        messages.send(player, "jobs.order.owed", "work", order.says(), "amount", Fees.format(order.pay()));
    }

    /** Ends orders out of time and pays what is owed. Once a minute. */
    public synchronized void tick() {
        long now = clock.getAsLong();
        book.all().forEach((player, ledger) -> {
            Order order = ledger.order();
            if (order == null) {
                return;
            }
            Player online = server.getPlayer(player);
            if (order.state() == Order.State.OPEN && now >= order.endsAt()) {
                if (book.putNow(player, ledger.with(order.in(Order.State.FAILED))) && online != null) {
                    hide(online);
                    messages.send(online, "jobs.order.failed", "work", order.says(),
                            "progress", String.valueOf(order.progress()), "units", String.valueOf(order.units()));
                }
            } else if (order.state() == Order.State.OWED) {
                if (!book.putNow(player, ledger.with(order.in(Order.State.PAID)))) {
                    return;
                }
                EconomyResult result = Fees.pay(player, order.pay(), "Order: " + order.says() + " (owed)", SOURCE);
                if (!result.succeeded()) {
                    book.putNow(player, ledger);
                } else if (online != null) {
                    messages.send(online, "jobs.order.paid-late", "amount", Fees.format(result.amount()));
                }
            }
        });
        book.flush();
    }

    /** Shows the running order's clock above the player, or takes it away. On the player's own thread. */
    public void bar(Player player) {
        Optional<Order> order = current(player.getUniqueId()).filter(OrderService::running);
        if (order.isEmpty()) {
            hide(player);
            return;
        }
        long now = clock.getAsLong();
        Order running = order.get();
        long left = Math.max(0, running.endsAt() - now);
        float share = (float) Math.clamp(left / (double) Math.max(1, running.endsAt() - running.startedAt()), 0.0, 1.0);
        Component name = messages.get("jobs.order.bar", "work", running.says(), "progress",
                String.valueOf(running.progress()), "units", String.valueOf(running.units()), "left", clockOf(left));
        BossBar bar = bars.computeIfAbsent(player.getUniqueId(), id -> {
            BossBar fresh = BossBar.bossBar(name, share, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            player.showBossBar(fresh);
            return fresh;
        });
        bar.name(name);
        bar.progress(share);
        bar.color(share < 0.2f ? BossBar.Color.RED : BossBar.Color.YELLOW);
    }

    /** "12:05", "1:02:03". */
    static String clockOf(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        return hours > 0 ? String.format("%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    public void hide(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    public void forget(UUID player) {
        bars.remove(player);
    }

    public void flush() {
        book.flush();
    }
}
