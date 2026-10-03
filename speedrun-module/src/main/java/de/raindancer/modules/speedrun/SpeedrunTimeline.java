package de.raindancer.modules.speedrun;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything that happened in one run, in order, against the run's own clock: every split, every
 * death, every pause, every hand on the clock. What a summary is drawn from and what the history
 * keeps — and what makes a run's time checkable afterwards: a pause or a clock edit is on the record
 * of the run it happened in, not only in a server log.
 *
 * <p>Safe from any thread: advancements, deaths and quits arrive on whichever region owns the player.
 */
public final class SpeedrunTimeline {

    /** What kind of thing an {@link Entry} is. */
    public enum Kind {
        /** The first participant reached a milestone — {@link Entry#detail()} is its id. */
        SPLIT,
        /** A participant died — the detail is what killed them, as the death message said. */
        DEATH,
        /** Every participant went offline and the clock stopped. */
        PAUSE,
        /** One came back and it ran again. */
        UNPAUSE,
        /** Somebody set the clock by hand — the detail is what it read before. */
        CLOCK_EDIT,
        /** The run was picked up over a world already played in ({@code /speedrunresume}). */
        RESUMED,
        /** Somebody was taken off the roster mid-run. */
        LEFT,
        /** Somebody joined mid-run. */
        JOINED,
        /** The run ended — the detail is why. */
        FINISH,
        /** A Runner was caught for good — {@link Entry#other()} by whom, if anybody. */
        CAUGHT,
        /** A Runner lost one of several lives — the detail is how many are left. */
        LIFE_LOST,
        /** A Runner was away too long and counted as caught. */
        CAUGHT_AWAY,
        /** A Hunter died — {@link Entry#other()} to whom, if anybody. */
        HUNTER_DIED,
        /** Somebody moved between sides — the detail is the side they are on now. */
        SIDE_CHANGED
    }

    /**
     * @param kind   what happened
     * @param at     the run's clock when it did
     * @param who    whose doing it was, or {@code null} for nobody's (a pause, the run itself)
     * @param detail the milestone id, the death message, the old clock reading, or the reason
     * @param other  a second player it was about — the catcher of a caught Runner — or {@code null}
     */
    public record Entry(Kind kind, Duration at, UUID who, String detail, UUID other) {

        public Entry {
            Objects.requireNonNull(kind, "kind");
            at = at == null || at.isNegative() ? Duration.ZERO : at;
            detail = detail == null ? "" : detail;
        }

        /** An entry about one player only. */
        public Entry(Kind kind, Duration at, UUID who, String detail) {
            this(kind, at, who, detail, null);
        }
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    /**
     * Splits the run at {@code milestoneId} — only the first time: a milestone is passed once per run,
     * by whoever got there first.
     *
     * @return whether this was the split, rather than somebody arriving after it
     */
    public synchronized boolean split(String milestoneId, Duration at, UUID who) {
        if (milestoneId == null || splitAt(milestoneId).isPresent()) {
            return false;
        }
        entries.add(new Entry(Kind.SPLIT, at, who, milestoneId));
        return true;
    }

    /** Adds anything other than a split. */
    public void record(Kind kind, Duration at, UUID who, String detail) {
        record(kind, at, who, detail, null);
    }

    /** The same, with a second player — the Hunter who caught a Runner, say. */
    public void record(Kind kind, Duration at, UUID who, String detail, UUID other) {
        if (kind == Kind.SPLIT) {
            throw new IllegalArgumentException("A split goes through split(), which keeps the first only.");
        }
        entries.add(new Entry(kind, at, who, detail, other));
    }

    /** When {@code milestoneId} was split, if it has been. */
    public Optional<Entry> splitAt(String milestoneId) {
        return entries.stream()
                .filter(entry -> entry.kind() == Kind.SPLIT && entry.detail().equals(milestoneId))
                .findFirst();
    }

    /** Every entry, in the order it happened. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public List<Entry> of(Kind kind) {
        return entries.stream().filter(entry -> entry.kind() == kind).toList();
    }

    public boolean has(Kind kind) {
        return entries.stream().anyMatch(entry -> entry.kind() == kind);
    }
}
