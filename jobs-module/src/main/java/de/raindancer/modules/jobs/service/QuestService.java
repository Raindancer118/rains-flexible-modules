package de.raindancer.modules.jobs.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.roles.HeldRole;
import de.raindancer.core.social.roles.PlayerRoles;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.jobs.QuestSettings;
import de.raindancer.modules.jobs.model.Quest;
import de.raindancer.modules.jobs.model.QuestDay;
import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.model.QuestTemplate;
import de.raindancer.modules.jobs.rules.QuestRule;
import de.raindancer.modules.jobs.store.QuestBook;
import de.raindancer.modules.jobs.store.QuestCatalogue;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Personal quests: hands each player their day's quests at the tier their balance puts them on, counts what
 * they do towards them, and pays each one the moment it is done — once, even across a restart.
 */
public final class QuestService {

    private static final String SOURCE = "jobs.quest";

    private final Server server;
    private final QuestCatalogue catalogue;
    private final QuestBook book;
    private final Messages messages;
    private final LogChannel log;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final Random random;
    private final QuestRule rule = new QuestRule();
    /** Blocks travelled but not yet whole, per player. */
    private final Map<UUID, Double> walked = new ConcurrentHashMap<>();
    private volatile QuestSettings settings;

    public QuestService(Server server, QuestCatalogue catalogue, QuestBook book, Messages messages, LogChannel log,
                        LongSupplier clock, ZoneId zone, Random random, QuestSettings settings) {
        this.server = server;
        this.catalogue = catalogue;
        this.book = book;
        this.messages = messages;
        this.log = log;
        this.clock = clock;
        this.zone = zone;
        this.random = random;
        settings(settings);
    }

    public void settings(QuestSettings updated) {
        this.settings = updated == null ? QuestSettings.DEFAULTS : updated;
    }

    public QuestSettings settings() {
        return settings;
    }

    public LocalDate todayDate() {
        return Instant.ofEpochMilli(clock.getAsLong()).atZone(zone).toLocalDate();
    }

    public Optional<QuestTemplate> template(Quest quest) {
        return catalogue.find(quest.template());
    }

    public int reload() {
        return catalogue.reload();
    }

    public List<String> problems() {
        return catalogue.problems();
    }

    /** Every block a mining quest could ask for — what Core's PlacedBlocks should remember. */
    public Set<Material> minedBlocks() {
        Set<Material> blocks = EnumSet.noneOf(Material.class);
        for (QuestTemplate quest : catalogue.all()) {
            if (quest.task() != QuestTask.MINE) {
                continue;
            }
            for (Material material : Material.values()) {
                if (!material.isLegacy() && material.isBlock() && quest.things().covers(material.name())) {
                    blocks.add(material);
                }
            }
        }
        return blocks;
    }

    // ---------------------------------------------------------------------------- the day

    /** This player's quests for today, handed out now if they have none yet. Empty while quests are off. */
    public synchronized QuestDay today(UUID player) {
        String today = todayDate().toString();
        Optional<QuestDay> held = book.of(player);
        if (held.isPresent() && held.get().day().equals(today)) {
            return held.get();
        }
        QuestSettings live = settings;
        if (!live.enabled()) {
            return new QuestDay(today, 0, held.map(this::owedOf).orElse(List.of()), List.of());
        }
        Money balance = Economies.current().map(bank -> bank.balance(player)).orElse(Money.ZERO);
        int tier = rule.tier(balance, Fees.amount(live.wealthStep() == null ? "" : live.wealthStep()), live.mostTier());
        String role = PlayerRoles.of(player).map(HeldRole::id).orElse("");
        List<String> yesterday = held.map(day -> day.quests().stream().map(Quest::template).toList()).orElse(List.of());
        List<Quest> quests = new ArrayList<>();
        for (QuestTemplate template : rule.pick(catalogue.all(), role, live.perDay(), live.perDayForRole(), yesterday, random)) {
            quests.add(new Quest(template.id(), rule.amount(template.amount(), tier, live.harderPercent()),
                    rule.pay(Fees.amount(template.pay()), tier, live.payMorePercent(),
                            template.forRole() ? live.roleBonusPercent() : 0, live.payScalePercent()),
                    0, Quest.State.OPEN));
        }
        // What the treasury still owes from an earlier day comes along, so it is never lost.
        held.map(this::owedOf).ifPresent(quests::addAll);
        QuestDay day = new QuestDay(today, tier, quests, yesterday);
        if (!book.putNow(player, day)) {
            book.put(player, day);
            log.warn("The quests of {} could not be written to quest-progress.yml now; tried again with the next save.",
                    player);
        }
        return day;
    }

    /** One more quest for today, by its name in quests.yml, at the player's tier — staff handing one out. */
    public synchronized Optional<Quest> give(UUID player, String id) {
        Optional<QuestTemplate> template = catalogue.find(id);
        QuestDay day = today(player);
        if (template.isEmpty() || day.quests().stream().anyMatch(quest -> quest.template().equals(template.get().id())
                && quest.open() && !quest.done())) {
            return Optional.empty();
        }
        Quest quest = fresh(template.get(), day.tier());
        List<Quest> quests = new ArrayList<>(day.quests());
        quests.add(quest);
        return book.putNow(player, new QuestDay(day.day(), day.tier(), quests, day.before(), day.rerolls()))
                ? Optional.of(quest) : Optional.empty();
    }

    /** A quest from its template at a tier, nothing done yet. */
    private Quest fresh(QuestTemplate template, int tier) {
        QuestSettings live = settings;
        return new Quest(template.id(), rule.amount(template.amount(), tier, live.harderPercent()),
                rule.pay(Fees.amount(template.pay()), tier, live.payMorePercent(),
                        template.forRole() ? live.roleBonusPercent() : 0, live.payScalePercent()),
                0, Quest.State.OPEN);
    }

    /** Free swaps left today. */
    public int rerollsLeft(UUID player) {
        return Math.max(0, settings.freeRerolls() - today(player).rerolls());
    }

    /**
     * Swaps one of today's quests, not begun, for another of its kind — one for anybody for one for anybody, a
     * role's for the same role's — at the same tier, using a free swap. Yesterday's are left out while others
     * are there.
     *
     * @return false, and nothing changed, when it may not be swapped or there is nothing to swap it for
     */
    public synchronized boolean reroll(UUID player, int index) {
        QuestDay day = today(player);
        if (index < 0 || index >= day.quests().size() || rerollsLeft(player) <= 0) {
            return false;
        }
        Quest quest = day.quests().get(index);
        Optional<QuestTemplate> was = template(quest);
        if (!quest.open() || quest.progress() > 0 || was.isEmpty()) {
            return false;
        }
        java.util.Set<String> taken = new java.util.HashSet<>();
        day.quests().forEach(each -> taken.add(each.template()));
        List<QuestTemplate> others = new ArrayList<>(catalogue.all().stream()
                .filter(each -> each.role().equals(was.get().role()) && !taken.contains(each.id())).toList());
        if (others.isEmpty()) {
            return false;
        }
        java.util.Collections.shuffle(others, random);
        others.sort(java.util.Comparator.comparing(each -> day.before().contains(each.id())));
        QuestDay swapped = day.with(index, fresh(others.getFirst(), day.tier()));
        return book.putNow(player, new QuestDay(swapped.day(), swapped.tier(), swapped.quests(), swapped.before(),
                day.rerolls() + 1));
    }

    public List<QuestTemplate> templates() {
        return catalogue.all();
    }

    private List<Quest> owedOf(QuestDay day) {
        return day.quests().stream().filter(quest -> quest.state() == Quest.State.OWED).toList();
    }

    /** Hands this player new quests for today, at their tier now. */
    public synchronized boolean reset(UUID player) {
        Optional<QuestDay> held = book.of(player);
        if (held.isEmpty()) {
            return true;
        }
        List<Quest> owed = owedOf(held.get());
        if (!owed.isEmpty()) {
            return book.putNow(player, new QuestDay("", held.get().tier(), owed, held.get().before(), 0));
        }
        return book.clear(player);
    }

    // ---------------------------------------------------------------------------- doing them

    /** Something done that may count: {@code thing} is the block, mob or fish, by its upper-case name. */
    public synchronized void progress(Player player, QuestTask task, String thing, int count) {
        if (count <= 0 || !settings.enabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        QuestDay day = today(id);
        for (int i = 0; i < day.quests().size(); i++) {
            Quest quest = day.quests().get(i);
            if (!quest.open() || quest.done()) {
                continue;
            }
            Optional<QuestTemplate> template = template(quest);
            if (template.isEmpty() || template.get().task() != task
                    || task != QuestTask.TRAVEL && !template.get().things().covers(thing)) {
                continue;
            }
            Quest moved = quest.plus(count);
            day = day.with(i, moved);
            if (moved.done()) {
                day = finish(player, day, i, template.get());
            } else {
                book.put(id, day);
                player.sendActionBar(messages.get("jobs.quest.progress", "quest", template.get().title(),
                        "progress", String.valueOf(moved.progress()), "amount", String.valueOf(moved.amount())));
            }
        }
    }

    /** Marks it paid and written before paying, so a crash in between never pays it twice. */
    private QuestDay finish(Player player, QuestDay day, int index, QuestTemplate template) {
        UUID id = player.getUniqueId();
        Quest quest = day.quests().get(index);
        QuestDay paid = day.with(index, quest.in(Quest.State.PAID));
        if (!book.putNow(id, paid)) {
            log.error("Quest {} of {} is done but quest-progress.yml cannot be written, so it is not paid yet.",
                    template.id(), player.getName());
            messages.send(player, "jobs.quest.not-saved");
            return day;
        }
        EconomyResult result = Fees.pay(id, quest.pay(), "Quest: " + template.title(), SOURCE);
        if (result.succeeded()) {
            messages.send(player, "jobs.quest.done", "quest", template.title(), "amount", Fees.format(result.amount()));
            return paid;
        }
        QuestDay owed = day.with(index, quest.in(Quest.State.OWED));
        if (!book.putNow(id, owed)) {
            book.put(id, owed);
        }
        log.warn("{} could not be paid for quest {}: {} — kept as owed, retried every minute.", player.getName(),
                template.id(), result.outcome());
        messages.send(player, "jobs.quest.owed", "quest", template.title(), "amount", Fees.format(quest.pay()));
        return owed;
    }

    /**
     * Blocks this player moved since the last sample; added up until there are whole ones to count.
     *
     * @return the whole blocks counted now, for anything else that counts travel
     */
    public int travelled(Player player, double blocks) {
        if (blocks <= 0) {
            return 0;
        }
        double total = walked.merge(player.getUniqueId(), blocks, Double::sum);
        int whole = (int) Math.floor(total);
        if (whole > 0) {
            walked.put(player.getUniqueId(), total - whole);
            progress(player, QuestTask.TRAVEL, "", whole);
        }
        return whole;
    }

    public void forget(UUID player) {
        walked.remove(player);
    }

    /** Pays what was owed and writes what was counted. Once a minute. */
    public synchronized void tick() {
        book.owing().forEach((player, day) -> {
            QuestDay now = day;
            for (int i = 0; i < now.quests().size(); i++) {
                Quest quest = now.quests().get(i);
                if (quest.state() != Quest.State.OWED) {
                    continue;
                }
                QuestDay paid = now.with(i, quest.in(Quest.State.PAID));
                if (!book.putNow(player, paid)) {
                    return;
                }
                String title = template(quest).map(QuestTemplate::title).orElse(quest.template());
                EconomyResult result = Fees.pay(player, quest.pay(), "Quest: " + title + " (owed)", SOURCE);
                if (!result.succeeded()) {
                    book.putNow(player, now);
                    return;
                }
                now = paid;
                Player online = server.getPlayer(player);
                if (online != null) {
                    messages.send(online, "jobs.quest.paid-late", "quest", title, "amount", Fees.format(result.amount()));
                }
            }
        });
        flush();
    }

    public void flush() {
        book.flush(todayDate());
    }
}
