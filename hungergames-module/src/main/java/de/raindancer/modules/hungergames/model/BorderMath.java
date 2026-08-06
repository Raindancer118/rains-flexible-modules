package de.raindancer.modules.hungergames.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The border's pure arithmetic: speeds, durations, conflict detection and computed resolution options.
 *
 * <h2>The one formula everything else is built from</h2>
 * Shrinking from {@code start} to {@code target} moves each of the four edges by half the diameter
 * difference, so the edge speed is {@code (start - target) / 2 / duration}. Every other method here is
 * that formula rearranged for a different unknown, or applied across a whole configuration instead of one
 * phase.
 *
 * <h2>Why validation and resolution are separate from the engine that runs the border</h2>
 * Because the fairness ceiling — {@code maxEdgeSpeed}, 2.5 blocks per second by default — is a promise to
 * every tribute that the border can never close in on them faster than that, and a promise kept by "the
 * engine clamps it at runtime" is a promise an admin cannot see broken before it happens. So a
 * configuration is checked here, in full, before a round ever starts: every conflict it produces, with
 * the numbers that make it a conflict, and every way to fix each one with its effects computed in advance
 * for the screen to show. {@code rules.BorderRules} — the thing that actually decides what the border does
 * on a given tick — trusts a configuration that passed this validation and never re-derives these numbers.
 */
public final class BorderMath {

    private BorderMath() {
    }

    // ==================== basic arithmetic ====================

    /** The edge speed, in blocks per second. */
    public static double edgeSpeed(double startSize, double targetSize, double durationSeconds) {
        if (durationSeconds <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return (startSize - targetSize) / 2.0 / durationSeconds;
    }

    /**
     * The duration needed to shrink at the given edge speed.
     *
     * <p>Zero returns {@link Duration#ZERO} rather than dividing.
     * {@code (startSize - targetSize) / 2.0 / 0.0} is {@code Infinity}, {@code Math.round(Infinity * 1000)} is
     * {@code Long.MAX_VALUE}, and {@code Duration.ofMillis} of that is a border scheduled to finish shrinking
     * in about 292 million years — which is not an error anywhere, just a border that never visibly moves and
     * a round nobody can finish. Zero is reachable: the edge speed comes from a config file by way of
     * {@code border.max-edge-speed}, and zero is what somebody types when they mean "do not shrink".
     *
     * <p>A negative speed is treated the same way. It means shrinking backwards, which is a growing border
     * expressed as a negative duration, and {@code Duration} is perfectly happy to hold one — so the
     * nonsensical value would travel a long way before anything noticed.
     *
     * <p>The guard is here rather than at the call sites because {@link #edgeSpeed} already guards its own
     * divisor. Two sibling functions where one checks and the other does not is an asymmetry somebody relies
     * on by accident.
     */
    public static Duration durationFor(double startSize, double targetSize, double edgeSpeed) {
        if (edgeSpeed <= 0 || !Double.isFinite(edgeSpeed)) {
            return Duration.ZERO;
        }
        double seconds = (startSize - targetSize) / 2.0 / edgeSpeed;
        if (!Double.isFinite(seconds) || seconds <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(Math.round(seconds * 1000));
    }

    /** The ceiling of a {@link BorderPhaseConfig.Mode#MAX_SPEED} phase: the smaller of the phase's own value and the global limit. */
    public static double speedCap(BorderSettings settings, BorderPhaseConfig phase) {
        return Math.min(phase.edgeSpeed().orElseThrow(), settings.maxEdgeSpeed());
    }

    /** The edge speed a phase actually runs at. */
    public static double impliedSpeed(BorderSettings settings, int phaseIndex) {
        BorderPhaseConfig phase = settings.phases().get(phaseIndex);
        double start = settings.startSizeOf(phaseIndex);
        return switch (phase.mode()) {
            case DURATION -> edgeSpeed(start, phase.targetSize(),
                    phase.duration().orElseThrow().toMillis() / 1000.0);
            case FIXED_SPEED -> phase.edgeSpeed().orElseThrow();
            // With a time anchor: the speed the preferred duration would need, clamped to the ceiling.
            // Without one: the ceiling itself.
            case MAX_SPEED -> phase.duration()
                    .map(preferred -> Math.min(
                            edgeSpeed(start, phase.targetSize(), preferred.toMillis() / 1000.0),
                            speedCap(settings, phase)))
                    .orElseGet(() -> speedCap(settings, phase));
        };
    }

    /** The effective shrink duration of a phase. */
    public static Duration effectiveDuration(BorderSettings settings, int phaseIndex) {
        BorderPhaseConfig phase = settings.phases().get(phaseIndex);
        double start = settings.startSizeOf(phaseIndex);
        return switch (phase.mode()) {
            case DURATION -> phase.duration().orElseThrow();
            case FIXED_SPEED, MAX_SPEED -> durationFor(start, phase.targetSize(),
                    impliedSpeed(settings, phaseIndex));
        };
    }

    // ==================== validation ====================

    /**
     * Checks a complete border configuration for contradictions.
     *
     * @param gameDuration the round's total length, for the "does this fit" checks, or empty
     */
    public static List<BorderConflict> validate(BorderSettings settings, Optional<Duration> gameDuration) {
        List<BorderConflict> conflicts = new ArrayList<>();
        Duration lastTriggerTime = null;

        for (int i = 0; i < settings.phases().size(); i++) {
            BorderPhaseConfig phase = settings.phases().get(i);
            double start = settings.startSizeOf(i);
            double target = phase.targetSize();

            if (target >= start) {
                conflicts.add(new BorderConflict(i, BorderConflict.Type.TARGET_NOT_SHRINKING, 0, start));
                continue; // the rest of this phase's checks would be meaningless
            }

            if (target < settings.minimumSize()) {
                conflicts.add(new BorderConflict(i, BorderConflict.Type.TARGET_BELOW_MINIMUM,
                        impliedSpeed(settings, i), settings.minimumSize()));
            }

            if (phase.mode() == BorderPhaseConfig.Mode.MAX_SPEED) {
                // Only checkable with a time anchor: is the anchor reachable under the ceiling? Without
                // one there is no time frame to violate, so there is nothing to flag.
                if (phase.duration().isPresent()) {
                    double needed = edgeSpeed(start, target,
                            phase.duration().get().toMillis() / 1000.0);
                    double cap = speedCap(settings, phase);
                    if (needed > cap) {
                        conflicts.add(new BorderConflict(i,
                                BorderConflict.Type.SPEED_EXCEEDS_MAX, needed, cap));
                    }
                }
            } else {
                double speed = impliedSpeed(settings, i);
                if (speed > settings.maxEdgeSpeed()) {
                    conflicts.add(new BorderConflict(i, BorderConflict.Type.SPEED_EXCEEDS_MAX,
                            speed, settings.maxEdgeSpeed()));
                }
            }

            if (phase.trigger().time().isPresent()) {
                Duration triggerTime = phase.trigger().time().get();

                if (lastTriggerTime != null && triggerTime.compareTo(lastTriggerTime) < 0) {
                    conflicts.add(new BorderConflict(i, BorderConflict.Type.PHASES_OUT_OF_ORDER,
                            0, lastTriggerTime.toSeconds()));
                }
                lastTriggerTime = triggerTime;

                if (gameDuration.isPresent()) {
                    Duration end = triggerTime.plus(effectiveDuration(settings, i));
                    if (end.compareTo(gameDuration.get()) > 0) {
                        conflicts.add(new BorderConflict(i, BorderConflict.Type.EXCEEDS_GAME_TIME,
                                impliedSpeed(settings, i), gameDuration.get().toSeconds()));
                    }
                }
            }
        }
        return conflicts;
    }

    // ==================== resolution options ====================

    /**
     * The concrete resolution options for a conflict, each with its effects pre-computed for the screen to
     * show. {@code Discard} is always the last option.
     */
    public static List<BorderResolution> resolutions(BorderSettings settings, BorderConflict conflict,
                                                       Optional<Duration> gameDuration) {
        BorderPhaseConfig phase = settings.phases().get(conflict.phaseIndex());
        double start = settings.startSizeOf(conflict.phaseIndex());
        double target = phase.targetSize();
        // The limit that matters: for MAX_SPEED the smaller of the phase's own cap and the global one —
        // otherwise the options offered would not actually resolve the conflict.
        double maxSpeed = phase.mode() == BorderPhaseConfig.Mode.MAX_SPEED
                ? speedCap(settings, phase)
                : settings.maxEdgeSpeed();
        List<BorderResolution> options = new ArrayList<>();

        switch (conflict.type()) {
            case SPEED_EXCEEDS_MAX -> {
                Duration slowEnough = durationFor(start, target, maxSpeed);
                options.add(new BorderResolution.AdjustDuration(slowEnough, maxSpeed));

                phase.duration().ifPresent(d -> {
                    double reachable = start - 2 * maxSpeed * (d.toMillis() / 1000.0);
                    if (reachable > 0 && reachable >= settings.minimumSize()) {
                        options.add(new BorderResolution.AdjustTarget(reachable, maxSpeed));
                    }
                });

                options.add(new BorderResolution.UseSpeedAsMax(slowEnough, maxSpeed));
            }
            case TARGET_BELOW_MINIMUM -> {
                double speedAtMinimum = switch (phase.mode()) {
                    case DURATION -> edgeSpeed(start, settings.minimumSize(),
                            phase.duration().orElseThrow().toMillis() / 1000.0);
                    case FIXED_SPEED, MAX_SPEED -> impliedSpeed(settings, conflict.phaseIndex());
                };
                options.add(new BorderResolution.AdjustTarget(settings.minimumSize(), speedAtMinimum));
            }
            case TARGET_NOT_SHRINKING -> {
                double proposal = Math.max(settings.minimumSize(), start / 2.0);
                if (proposal < start) {
                    options.add(new BorderResolution.AdjustTarget(proposal, maxSpeed));
                }
            }
            case EXCEEDS_GAME_TIME -> {
                Duration effDuration = effectiveDuration(settings, conflict.phaseIndex());
                Duration triggerTime = phase.trigger().time().orElse(Duration.ZERO);

                gameDuration.ifPresent(game -> {
                    Duration newStart = game.minus(effDuration);
                    if (!newStart.isNegative()) {
                        options.add(new BorderResolution.ShiftStart(newStart));
                    }
                    Duration remaining = game.minus(triggerTime);
                    if (remaining.isPositive()) {
                        options.add(new BorderResolution.AdjustDuration(remaining,
                                edgeSpeed(start, target, remaining.toMillis() / 1000.0)));
                    }
                });
                options.add(new BorderResolution.AdjustGameTime(triggerTime.plus(effDuration)));
            }
            case PHASES_OUT_OF_ORDER -> {
                if (conflict.phaseIndex() > 0) {
                    int prev = conflict.phaseIndex() - 1;
                    settings.phases().get(prev).trigger().time().ifPresent(prevTime ->
                            options.add(new BorderResolution.ShiftStart(
                                    prevTime.plus(effectiveDuration(settings, prev)))));
                }
            }
        }

        options.add(new BorderResolution.Discard());
        return options;
    }

    // ==================== application ====================

    /**
     * The result of {@link #apply}: the new border configuration, and — only for
     * {@link BorderResolution.AdjustGameTime} — the new total game duration.
     */
    public record ApplyResult(BorderSettings settings, Optional<Duration> newGameDuration) {
    }

    /** Applies a confirmed resolution option, producing new, untouched objects. */
    public static ApplyResult apply(BorderSettings settings, int phaseIndex, BorderResolution resolution) {
        BorderPhaseConfig phase = settings.phases().get(phaseIndex);

        return switch (resolution) {
            case BorderResolution.AdjustDuration r -> new ApplyResult(
                    settings.withPhase(phaseIndex, phase.withDuration(r.newDuration())),
                    Optional.empty());
            case BorderResolution.AdjustTarget r -> new ApplyResult(
                    settings.withPhase(phaseIndex, phase.withTargetSize(r.newTarget())),
                    Optional.empty());
            case BorderResolution.ShiftStart r -> new ApplyResult(
                    settings.withPhase(phaseIndex, phase.withTrigger(new BorderTrigger(
                            Optional.of(r.newStart()), phase.trigger().aliveBelow()))),
                    Optional.empty());
            case BorderResolution.AdjustGameTime r -> new ApplyResult(
                    settings, Optional.of(r.newGameDuration()));
            case BorderResolution.UseSpeedAsMax r -> new ApplyResult(
                    settings.withPhase(phaseIndex, BorderPhaseConfig.ofMaxSpeed(
                            phase.trigger(), phase.targetSize(),
                            phase.edgeSpeed().orElse(settings.maxEdgeSpeed()))),
                    Optional.empty());
            case BorderResolution.Discard ignored -> new ApplyResult(settings, Optional.empty());
        };
    }
}
