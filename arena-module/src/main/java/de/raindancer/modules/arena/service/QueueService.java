package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.ArenaSettings;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.model.Pit;
import de.raindancer.modules.arena.model.QueueEntry;
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
        Scheduling.global(plugin, this::tick);
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
        synchronized (this) {
            group.players().forEach(player -> waiting.removeIf(entry -> entry.player().equals(player)));
            agreed.add(group);
        }
        Scheduling.global(plugin, this::tick);
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
        synchronized (this) {
            snapshot = List.copyOf(waiting);
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
