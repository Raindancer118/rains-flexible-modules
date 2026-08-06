package de.raindancer.modules.hungergames.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * One configured border phase: when it starts, what it shrinks to, and how fast.
 *
 * <h2>Why three modes rather than one</h2>
 * A server thinks about a phase in one of two ways — "shrink over twenty minutes, whatever speed that
 * takes" or "shrink at exactly one block a second, however long that takes" — and {@link Mode#DURATION}
 * and {@link Mode#FIXED_SPEED} are exactly those two, letting whichever number the owner actually cares
 * about be the one they set. {@link Mode#MAX_SPEED} is the third case, and the one worth the most words:
 * a speed given there is a ceiling, not a target. Without a time anchor the border shrinks as fast as the
 * ceiling allows; with one — the {@code prefer:} syntax the config parser reads a duration for — it
 * shrinks at whatever speed reaches the target in that time, only clamped to the ceiling when the anchor
 * would demand more. That is what lets an owner say "twenty minutes, but never faster than the fairness
 * limit" in one phase instead of hand-computing whether twenty minutes is even achievable.
 *
 * @param trigger    what starts the phase (time and/or a tribute count)
 * @param targetSize the diameter it shrinks to, in blocks
 * @param mode       how speed and duration are worked out
 * @param duration   the shrink duration ({@link Mode#DURATION}), or the preferred duration used as a time
 *                   anchor ({@link Mode#MAX_SPEED}, optional)
 * @param edgeSpeed  the edge speed in blocks per second ({@link Mode#FIXED_SPEED} and {@link Mode#MAX_SPEED})
 */
public record BorderPhaseConfig(
        BorderTrigger trigger,
        double targetSize,
        Mode mode,
        Optional<Duration> duration,
        Optional<Double> edgeSpeed) {

    /** How a phase's shrink speed is worked out. */
    public enum Mode {
        /** A fixed duration; the speed follows from the distance shrunk. */
        DURATION,
        /** A fixed edge speed; the duration follows from the distance shrunk. */
        FIXED_SPEED,
        /**
         * The speed is a ceiling. With {@code duration} set (a time anchor) the speed is worked out from
         * the preferred duration and only clamped to the ceiling when needed; without an anchor the phase
         * shrinks at the ceiling itself.
         */
        MAX_SPEED
    }

    public BorderPhaseConfig {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(mode, "mode");
        if (targetSize <= 0) {
            throw new IllegalArgumentException("targetSize must be > 0");
        }
        switch (mode) {
            case DURATION -> {
                if (duration.isEmpty()) {
                    throw new IllegalArgumentException("mode DURATION needs a duration");
                }
            }
            case FIXED_SPEED, MAX_SPEED -> {
                if (edgeSpeed.isEmpty() || edgeSpeed.get() <= 0) {
                    throw new IllegalArgumentException("mode " + mode + " needs an edgeSpeed > 0");
                }
            }
        }
    }

    public static BorderPhaseConfig ofDuration(BorderTrigger trigger, double targetSize, Duration duration) {
        return new BorderPhaseConfig(trigger, targetSize, Mode.DURATION, Optional.of(duration), Optional.empty());
    }

    public static BorderPhaseConfig ofFixedSpeed(BorderTrigger trigger, double targetSize, double edgeSpeed) {
        return new BorderPhaseConfig(trigger, targetSize, Mode.FIXED_SPEED, Optional.empty(), Optional.of(edgeSpeed));
    }

    public static BorderPhaseConfig ofMaxSpeed(BorderTrigger trigger, double targetSize, double edgeSpeed) {
        return new BorderPhaseConfig(trigger, targetSize, Mode.MAX_SPEED, Optional.empty(), Optional.of(edgeSpeed));
    }

    /** {@link Mode#MAX_SPEED} with a time anchor: the preferred duration, capped at {@code edgeSpeed}. */
    public static BorderPhaseConfig ofMaxSpeed(BorderTrigger trigger, double targetSize,
                                                double edgeSpeed, Duration preferredDuration) {
        return new BorderPhaseConfig(trigger, targetSize, Mode.MAX_SPEED,
                Optional.of(preferredDuration), Optional.of(edgeSpeed));
    }

    public BorderPhaseConfig withTargetSize(double newTarget) {
        return new BorderPhaseConfig(trigger, newTarget, mode, duration, edgeSpeed);
    }

    public BorderPhaseConfig withDuration(Duration newDuration) {
        // MAX_SPEED keeps its mode — the duration there is the time anchor, not a switch to DURATION.
        Mode newMode = mode == Mode.MAX_SPEED ? Mode.MAX_SPEED : Mode.DURATION;
        return new BorderPhaseConfig(trigger, targetSize, newMode, Optional.of(newDuration), edgeSpeed);
    }

    public BorderPhaseConfig withTrigger(BorderTrigger newTrigger) {
        return new BorderPhaseConfig(newTrigger, targetSize, mode, duration, edgeSpeed);
    }

    public BorderPhaseConfig withMode(Mode newMode) {
        return new BorderPhaseConfig(trigger, targetSize, newMode, duration, edgeSpeed);
    }
}
