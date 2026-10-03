package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The splits of one run: which milestones it can pass, which it has, how each compares, and what
 * the run's HUD shows besides them. One per run; the split itself is written to the session's
 * {@link SpeedrunTimeline}, so it is on the run's record whatever happens here.
 *
 * <p>Safe from any thread — milestones are reached on whichever region owns the racer.
 */
public final class SpeedrunSplitTracker {

    private static final LogChannel log = Log.of("speedrun");

    /** A milestone just reached, by whom, at what time on the run's clock. */
    public record Split(SpeedrunMilestone milestone, Duration at, UUID who) {
    }

    private final SpeedrunSession session;
    private final Map<String, SpeedrunMilestone> declared = new ConcurrentHashMap<>();
    private final List<Consumer<Split>> listeners = new CopyOnWriteArrayList<>();
    private final List<Function<UUID, List<Component>>> hudLines = new CopyOnWriteArrayList<>();
    private final Map<String, SpeedrunComparison> comparisons = new ConcurrentHashMap<>();
    private final AtomicInteger pearls = new AtomicInteger();
    private volatile SpeedrunHistory history;
    private volatile SpeedrunCategory category;

    public SpeedrunSplitTracker(SpeedrunSession session) {
        this.session = Objects.requireNonNull(session, "session");
        SpeedrunMilestones.BUILT_IN.forEach(milestone -> declared.put(milestone.id(), milestone));
    }

    /** What splits are compared against: the history, in the run's own category. */
    public void compareWith(SpeedrunHistory history, SpeedrunCategory category) {
        this.history = history;
        this.category = category;
    }

    public Optional<SpeedrunCategory> category() {
        return Optional.ofNullable(category);
    }

    /** A milestone of a game mode's own; a built-in id cannot be redeclared. */
    public SpeedrunMilestone declare(String id, String label, Material icon) {
        SpeedrunMilestone milestone = new SpeedrunMilestone(id, label, icon);
        SpeedrunMilestone existing = declared.putIfAbsent(milestone.id(), milestone);
        return existing == null ? milestone : existing;
    }

    /** Every milestone this run can pass, in order: the built-ins, a mode's own, the finish. */
    public List<SpeedrunMilestone> milestones() {
        List<SpeedrunMilestone> all = new ArrayList<>(declared.values());
        all.sort(Comparator.comparingInt((SpeedrunMilestone m) -> SpeedrunMilestones.order(m.id()))
                .thenComparing(SpeedrunMilestone::id));
        return all;
    }

    public Optional<SpeedrunMilestone> milestone(String id) {
        return Optional.ofNullable(id == null ? null : declared.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * {@code who} reached {@code milestoneId}. The first to reach it splits the run; anybody after
     * changes nothing. Ignored once the run is over, and for a milestone nobody declared.
     *
     * @return whether this was the split
     */
    public boolean reach(String milestoneId, UUID who) {
        Optional<SpeedrunMilestone> milestone = milestone(milestoneId);
        if (milestone.isEmpty() || session.state() == SpeedrunState.FINISHED
                && !SpeedrunMilestones.FINISH.id().equals(milestone.get().id())) {
            return false;
        }
        Duration at = session.elapsed();
        if (!session.timeline().split(milestone.get().id(), at, who)) {
            return false;
        }
        Split split = new Split(milestone.get(), at, who);
        for (Consumer<Split> listener : listeners) {
            try {
                listener.accept(split);
            } catch (RuntimeException broken) {
                log.error(broken, "A split listener threw.");
            }
        }
        return true;
    }

    /** Told about every split, once, as it happens. */
    public void onSplit(Consumer<Split> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * The racers picked up {@code amount} more ender pearls between them; reaching {@code target}
     * splits {@link SpeedrunMilestones#PEARLS}.
     */
    public void pearlsPickedUp(UUID who, int amount, int target) {
        if (amount <= 0 || session.state() == SpeedrunState.FINISHED) {
            return;
        }
        if (pearls.addAndGet(amount) >= target) {
            reach(SpeedrunMilestones.PEARLS.id(), who);
        }
    }

    public int pearls() {
        return pearls.get();
    }

    /** Whether {@code milestone} is still to come in this run. */
    public boolean timelineHasNo(SpeedrunMilestone milestone) {
        return session.timeline().splitAt(milestone.id()).isEmpty();
    }

    /** The splits so far, in the order they happened. */
    public List<Split> splits() {
        List<Split> done = new ArrayList<>();
        for (SpeedrunTimeline.Entry entry : session.timeline().of(SpeedrunTimeline.Kind.SPLIT)) {
            milestone(entry.detail()).ifPresent(milestone -> done.add(new Split(milestone, entry.at(), entry.who())));
        }
        return done;
    }

    public Optional<Split> last() {
        List<Split> done = splits();
        return done.isEmpty() ? Optional.empty() : Optional.of(done.getLast());
    }

    /** The first milestone, in order, nobody has reached yet — the finish when everything else is done. */
    public Optional<SpeedrunMilestone> next() {
        return milestones().stream()
                .filter(milestone -> session.timeline().splitAt(milestone.id()).isEmpty())
                .findFirst();
    }

    /** How {@code split} stands for {@code viewer} — worked out once per viewer and kept for the run. */
    public SpeedrunComparison compare(Split split, UUID viewer) {
        SpeedrunHistory against = history;
        SpeedrunCategory in = category;
        if (against == null || in == null) {
            return SpeedrunComparison.NOTHING_TO_COMPARE;
        }
        return comparisons.computeIfAbsent(split.milestone().id() + "|" + viewer,
                key -> SpeedrunComparison.of(against, in, viewer, split.milestone().id(), split.at()));
    }

    /** Lines a game mode adds to everybody's HUD — asked per viewer, every time the HUD is drawn. */
    public void addHudLines(Function<UUID, List<Component>> lines) {
        if (lines != null) {
            hudLines.add(lines);
        }
    }

    public List<Component> hudLinesFor(UUID viewer) {
        List<Component> all = new ArrayList<>();
        for (Function<UUID, List<Component>> provider : hudLines) {
            try {
                List<Component> given = provider.apply(viewer);
                if (given != null) {
                    all.addAll(given);
                }
            } catch (RuntimeException broken) {
                log.error(broken, "A HUD line provider threw.");
            }
        }
        return all;
    }

    /** What each milestone was called — kept with the run so a summary can still name a mode's own. */
    public Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        milestones().forEach(milestone -> labels.put(milestone.id(), milestone.label()));
        return labels;
    }
}
