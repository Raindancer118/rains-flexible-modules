package de.raindancer.modules.moderation.model;

import de.raindancer.core.moderation.punishment.Durations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * How long a punishment is for.
 *
 * <h2>Why this exists when Core already has {@code Durations}</h2>
 * It does not replace it — every string this reads is read <em>by</em> {@link Durations}, which is
 * itself four lines over {@code Times}. What this adds is the one distinction a duration alone cannot
 * make: {@code Durations.parse} answers empty both for "for ever" and for something it could not read,
 * and those two must never be confused. A moderator who types {@code 2 hours} with a space has made a
 * typo; treating it as {@code perm} is a permanent ban nobody meant to hand out, and every plugin that
 * has conflated the two has handed one out.
 *
 * <p>So: {@link #parse} gives a permanent sentence only for the words that mean permanent, and nothing
 * at all for the typo. The caller then has to say what happens next, which is the point.
 *
 * @param howLong how long, or null for a punishment that ends when somebody says so
 */
public record Sentence(Duration howLong) {

    private static final Sentence FOR_EVER = new Sentence(null);

    /** Until somebody lifts it. */
    public static Sentence forEver() {
        return FOR_EVER;
    }

    /** For this long. Refuses nothing, zero and negative, none of which are a length. */
    public static Sentence of(Duration howLong) {
        if (howLong == null || howLong.isZero() || howLong.isNegative()) {
            throw new IllegalArgumentException(
                    "a sentence is either for ever or for a positive length of time, not " + howLong);
        }
        return new Sentence(howLong);
    }

    /**
     * What somebody typed, or empty when they typed something unreadable.
     *
     * <p>Empty is <b>not</b> "for ever" — see the class note. {@code Durations.isForEver} is what
     * decides that, and it is asked first.
     */
    public static Optional<Sentence> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (Durations.isForEver(text)) {
            return Optional.of(FOR_EVER);
        }
        return Durations.parse(text)
                .filter(length -> !length.isZero() && !length.isNegative())
                .map(Sentence::new);
    }

    public boolean isPermanent() {
        return howLong == null;
    }

    /**
     * How long, when it is for a length of time.
     *
     * <p>Named differently from the component it reads because a record's accessor cannot change its
     * return type, and here the caller should be made to say what happens when there is no length.
     */
    public Optional<Duration> length() {
        return Optional.ofNullable(howLong);
    }

    /**
     * The length as Core wants it.
     *
     * <p>{@code Punishments.punish} takes a null length for a punishment that never ends, so this is
     * the one place the module deliberately produces one — rather than every call site rediscovering
     * that null means permanent.
     */
    public Duration orNull() {
        return howLong;
    }

    /** When a punishment starting now would stop applying, or null for one that never does. */
    public Instant endingAt(Instant from) {
        return howLong == null ? null : from.plus(howLong);
    }

    /** How long this is, in the words somebody would use. */
    public String describe() {
        return howLong == null ? "for ever" : Durations.describe(howLong);
    }
}
