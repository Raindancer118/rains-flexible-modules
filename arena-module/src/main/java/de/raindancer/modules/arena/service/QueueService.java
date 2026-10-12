package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.ArenaSettings;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.model.Pit;
import de.raindancer.modules.arena.model.QueueEntry;
import de.raindancer.modules.arena.rules.OpenQueueRule;
import de.raindancer.modules.arena.rules.PairingRule;
import de.raindancer.modules.arena.rules.PitRule;
import de.raindancer.modules.arena.rules.QueueRule;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Waiting for a fight, and starting fights as pits come free. Agreed fights (accepted invites) go ahead of the queue;
 * then the queue, by kind of fight, oldest first. Whoever waits sees their place above the hotbar.
 */
public final class QueueService implements IArenaService {

    private static final String BAR = "arena-queue";

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    private final ActionBars bars;
    private final ArenaService arena;
    private final MatchService matches;
    private final PairingRule pairing = new PairingRule();
    private final PitRule pits = new PitRule();
    private final QueueRule gate = new QueueRule();
    private final OpenQueueRule openRule = new OpenQueueRule();
    private final java.util.function.Predicate<String> kitKnown;
    private final java.util.function.BooleanSupplier survivalUp;
    private final List<QueueEntry> waiting = new ArrayList<>();
    private final Deque<PairingRule.Group> agreed = new ArrayDeque<>();
    private final LongSupplier clock;
    private volatile ArenaSettings settings;
    private ScheduledTask timer;
    private volatile java.util.function.BiConsumer<UUID, MatchSpec> consent = (player, spec) -> {
    };

    /** Told whenever somebody agrees here to a stake or to their own gear — survival takes neither without it. */
    public void consent(java.util.function.BiConsumer<UUID, MatchSpec> announce) {
        this.consent = announce;
    }

    /** That somebody agreed to this fight, for survival to know. */
    public void agreed(UUID player, MatchSpec spec) {
        consent.accept(player, spec);
    }

    public QueueService(Plugin plugin, Server server, Messages messages, ActionBars bars, ArenaService arena,
                        MatchService matches, LongSupplier clock, ArenaSettings settings,
                        java.util.function.Predicate<String> kitKnown, java.util.function.BooleanSupplier survivalUp) {
        this.kitKnown = kitKnown;
        this.survivalUp = survivalUp;
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.bars = bars;
        this.arena = arena;
        this.matches = matches;
        this.clock = clock;
        this.settings = settings;
    }

    @Override
    public void settings(ArenaSettings settings) {
        this.settings = settings;
    }

    public void start() {
        timer = Scheduling.globalTimer(plugin, 40L, 40L, task -> {
            tick();
            showPlaces();
        });
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
        }
    }

    public void join(Player player, MatchSpec spec) {
        UUID id = player.getUniqueId();
        if (refused(player, spec)) {
            return;
        }
        consent.accept(id, spec);
        synchronized (this) {
            waiting.removeIf(entry -> entry.player().equals(id));
            waiting.add(new QueueEntry(id, spec, clock.getAsLong()));
        }
        messages.send(player, "arena.queue.joined", "mode", spec.mode().title(), "kit", kitName(spec),
                "stake", spec.stake());
        announce(spec);
        Scheduling.global(plugin, this::tick);
    }

    /** The fights in the queue that still wait for more people. */
    public List<OpenQueueRule.Open> openQueues() {
        List<QueueEntry> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(waiting);
        }
        return openRule.open(snapshot);
    }

    /** Tells somebody arriving in the hub which fights they could join right now. @return how many */
    public int tellOpen(Player viewer) {
        List<OpenQueueRule.Open> open = openRule.forViewer(openQueues(), viewer.getUniqueId());
        open.forEach(each -> sendOpen(viewer, each));
        return open.size();
    }

    /** Somebody joined a fight that still waits for more: everybody free in the hub hears they can join. */
    private void announce(MatchSpec spec) {
        openQueues().stream().filter(each -> each.spec().queueKey().equals(spec.queueKey())).findFirst()
                .ifPresent(open -> server.getOnlinePlayers().stream()
                        .filter(viewer -> !open.waiting().contains(viewer.getUniqueId()))
                        .filter(viewer -> !matches.inFight(viewer.getUniqueId()))
                        .forEach(viewer -> sendOpen(viewer, open)));
    }

    private void sendOpen(Player viewer, OpenQueueRule.Open open) {
        Player first = server.getPlayer(open.waiting().getFirst());
        if (first == null) {
            return;
        }
        MatchSpec spec = open.spec();
        messages.send(viewer, spec.stake() > 0 ? "arena.queue.open-stake" : "arena.queue.open",
                "player", first.getName(), "mode", spec.mode().title(), "kit", kitName(spec), "stake", spec.stake(),
                "waiting", open.waiting().size(), "needed", open.needed());
    }

    /** Joins the same fight somebody is waiting for — the [Join] button. @return false when they are not waiting */
    public boolean joinLike(Player player, String name) {
        Player other = name == null ? null : server.getPlayerExact(name);
        Optional<QueueEntry> theirs = other == null ? Optional.empty() : entryOf(other.getUniqueId());
        if (theirs.isEmpty()) {
            messages.send(player, "arena.queue.join-gone", "player", name == null ? "?" : name);
            return false;
        }
        if (other.getUniqueId().equals(player.getUniqueId())) {
            messages.send(player, "arena.queue.join-yourself");
            return false;
        }
        join(player, theirs.get().spec());
        return true;
    }

    /** Whether this fight may not be waited for; says why when it may not. */
    public boolean refused(Player player, MatchSpec spec) {
        Optional<String> refusal = gate.refusal(new QueueRule.Facts(matches.inFight(player.getUniqueId()),
                arena.layout().pits().stream().anyMatch(Pit::isReady), spec.isOwnGear() || kitKnown.test(spec.kit()),
                spec.stake(), settings.mostStake(), spec.stake() > 0 || spec.isOwnGear(), survivalUp.getAsBoolean()));
        refusal.ifPresent(key -> messages.send(player, key, "kit", spec.kit(), "most", settings.mostStake()));
        return refusal.isPresent();
    }

    public boolean leave(UUID player) {
        boolean was;
        synchronized (this) {
            was = waiting.removeIf(entry -> entry.player().equals(player));
        }
        bars.clear(player, BAR);
        return was;
    }

    public synchronized Optional<QueueEntry> entryOf(UUID player) {
        return waiting.stream().filter(entry -> entry.player().equals(player)).findFirst();
    }

    /** An agreed fight, ahead of the queue. */
    public void reserve(PairingRule.Group group) {
        for (UUID member : group.players()) {
            Player online = server.getPlayer(member);
            if (online != null && refused(online, group.spec())) {
                return;
            }
        }
        boolean pitFree;
        synchronized (this) {
            group.players().forEach(player -> waiting.removeIf(entry -> entry.player().equals(player)));
            replaceOverlapping(agreed, group);
            pitFree = pits.free(arena.layout().pits(), matches.busyPits()).isPresent();
        }
        if (!pitFree) {
            group.players().stream().map(server::getPlayer).filter(java.util.Objects::nonNull)
                    .forEach(player -> messages.send(player, "arena.invite.pit-wait"));
        }
        Scheduling.global(plugin, this::tick);
    }

    /** A newer agreement wins: whoever is in it is taken out of any older one still waiting for a pit. */
    static void replaceOverlapping(Deque<PairingRule.Group> agreed, PairingRule.Group newer) {
        agreed.removeIf(older -> !java.util.Collections.disjoint(older.players(), newer.players()));
        agreed.add(newer);
    }

    /** Starts every fight there is a free pit for. Global thread. */
    public void tick() {
        List<PairingRule.Group> starting = new ArrayList<>();
        List<Pit> chosen = new ArrayList<>();
        synchronized (this) {
            java.util.Set<String> busy = new java.util.HashSet<>(matches.busyPits());
            while (!agreed.isEmpty()) {
                PairingRule.Group next = agreed.peek();
                if (next.players().stream().anyMatch(player -> server.getPlayer(player) == null || matches.inFight(player))) {
                    agreed.poll();
                    continue;
                }
                Optional<Pit> pit = pits.free(arena.layout().pits(), busy);
                if (pit.isEmpty()) {
                    break;
                }
                agreed.poll();
                busy.add(pit.get().id());
                starting.add(next);
                chosen.add(pit.get());
            }
            waiting.removeIf(entry -> server.getPlayer(entry.player()) == null || matches.inFight(entry.player()));
            for (PairingRule.Group group : pairing.groups(List.copyOf(waiting), clock.getAsLong(), settings.ffaWaitMillis())) {
                Optional<Pit> pit = pits.free(arena.layout().pits(), busy);
                if (pit.isEmpty()) {
                    break;
                }
                busy.add(pit.get().id());
                waiting.removeIf(entry -> group.players().contains(entry.player()));
                starting.add(group);
                chosen.add(pit.get());
            }
        }
        for (int index = 0; index < starting.size(); index++) {
            starting.get(index).players().forEach(player -> bars.clear(player, BAR));
            matches.begin(starting.get(index), chosen.get(index));
        }
    }

    private void showPlaces() {
        List<QueueEntry> snapshot;
        List<PairingRule.Group> agreedNow;
        synchronized (this) {
            snapshot = List.copyOf(waiting);
            agreedNow = List.copyOf(agreed);
        }
        for (PairingRule.Group group : agreedNow) {
            for (UUID member : group.players()) {
                String others = group.players().stream().filter(other -> !other.equals(member))
                        .map(server::getPlayer).filter(java.util.Objects::nonNull).map(Player::getName)
                        .collect(java.util.stream.Collectors.joining(", "));
                bars.show(member, BAR, messages.get("arena.queue.bar-agreed", "mode", group.spec().mode().title(),
                                "kit", kitName(group.spec()), "others", others),
                        Duration.ofSeconds(3), ActionBarPriority.LOW);
            }
        }
        for (QueueEntry entry : snapshot) {
            List<QueueEntry> same = snapshot.stream()
                    .filter(other -> other.spec().queueKey().equals(entry.spec().queueKey())).toList();
            int place = same.indexOf(entry) + 1;
            bars.show(entry.player(), BAR, messages.get("arena.queue.bar", "mode", entry.spec().mode().title(),
                            "kit", kitName(entry.spec()), "place", place, "waiting", same.size(),
                            "needed", entry.spec().players()),
                    Duration.ofSeconds(3), ActionBarPriority.LOW);
        }
    }

    private static String kitName(MatchSpec spec) {
        return spec.isOwnGear() ? "own gear" : spec.kit();
    }

    public void forget(UUID player) {
        leave(player);
    }
}
