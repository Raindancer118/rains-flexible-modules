package de.raindancer.modules.hungergames.rules;

import de.raindancer.modules.hungergames.model.BorderMath;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.BorderSettings;

import java.time.Duration;
import java.util.Optional;

/**
 * Decides, on a tick, whether the border's next phase should start and what shrink command that phase
 * asks for. Applying the answer to the actual world border is a service's job; this only decides.
 *
 * <h2>Why "next phase index" is a parameter and never a field</h2>
 * The source engine this was ported from kept that index as mutable state on the object, which reads
 * naturally for a single running round and breaks two things this module needs. It is not askable
 * speculatively — a screen wanting to preview "what would the border do right now" would have to tick a
 * live object and could accidentally advance the real round. And it does not survive a restart on its
 * own: the round's true progress is whatever is in the persisted {@code SessionSnapshot}, and an object
 * with its own copy of "where I am" is a second answer to that question, which is exactly the kind of
 * second source of truth this module has learned to avoid elsewhere too (see Core's
 * {@code de.raindancer.core.social.team.Teams}, which refuses to let two team registries ever disagree
 * about a colour). So the index travels as a value — read from the snapshot, ticked, the
 * returned index written back — and this class is left with nothing to disagree with the snapshot about.
 */
public final class BorderRules implements IHungerGamesRule {

    /**
     * A shrink to run.
     *
     * @param targetSize     the target diameter, in blocks
     * @param duration       how long the shrink takes
     * @param effectiveSpeed the resulting edge speed, in blocks per second
     */
    public record ShrinkCommand(double targetSize, Duration duration, double effectiveSpeed) {
    }

    /**
     * The outcome of one {@link #tick}.
     *
     * @param command         the shrink to run, if a phase started this tick
     * @param nextPhaseIndex  the index to persist and pass to the next tick — advanced past the phase that
     *                        just fired, whether or not it produced a command
     */
    public record TickResult(Optional<ShrinkCommand> command, int nextPhaseIndex) {
    }

    /**
     * Whether every configured phase has already fired.
     *
     * @param nextPhaseIndex the index carried from the previous tick, or {@code 0} for a fresh round
     */
    public boolean isFinished(BorderSettings settings, int nextPhaseIndex) {
        return nextPhaseIndex >= settings.phases().size();
    }

    /**
     * Checks whether the next configured phase should start, and works out its shrink command.
     *
     * @param settings       the round's border configuration
     * @param nextPhaseIndex the index carried from the previous tick, or {@code 0} for a fresh round
     * @param elapsed        virtual game time since {@code RUNNING} began
     * @param aliveCount     the number of tributes currently alive
     * @param currentSize    the border's current diameter
     */
    public TickResult tick(BorderSettings settings, int nextPhaseIndex, Duration elapsed, int aliveCount,
                            double currentSize) {
        if (isFinished(settings, nextPhaseIndex)) {
            return new TickResult(Optional.empty(), nextPhaseIndex);
        }

        BorderPhaseConfig phase = settings.phases().get(nextPhaseIndex);
        if (!phase.trigger().isTriggered(elapsed, aliveCount)) {
            return new TickResult(Optional.empty(), nextPhaseIndex);
        }
        int advanced = nextPhaseIndex + 1;

        // The minimum size is enforced hard here, and the current size is the true starting point — an
        // admin who has already nudged the border by hand is not overridden by the phase's own idea of
        // where it started.
        double target = Math.max(phase.targetSize(), settings.minimumSize());
        if (target >= currentSize) {
            return new TickResult(Optional.empty(), advanced); // nothing left to shrink
        }

        double speed;
        Duration duration;
        switch (phase.mode()) {
            case DURATION -> {
                duration = phase.duration().orElseThrow();
                speed = BorderMath.edgeSpeed(currentSize, target, duration.toMillis() / 1000.0);
            }
            case FIXED_SPEED -> {
                speed = phase.edgeSpeed().orElseThrow();
                duration = BorderMath.durationFor(currentSize, target, speed);
            }
            case MAX_SPEED -> {
                double cap = Math.min(phase.edgeSpeed().orElseThrow(), settings.maxEdgeSpeed());
                // With a time anchor: the speed the preferred duration needs, clamped to the ceiling.
                // Without one: the ceiling itself.
                speed = phase.duration()
                        .map(preferred -> Math.min(cap, BorderMath.edgeSpeed(
                                currentSize, target, preferred.toMillis() / 1000.0)))
                        .orElse(cap);
                duration = BorderMath.durationFor(currentSize, target, speed);
            }
            default -> throw new IllegalStateException("Unknown mode: " + phase.mode());
        }

        return new TickResult(Optional.of(new ShrinkCommand(target, duration, speed)), advanced);
    }
}
