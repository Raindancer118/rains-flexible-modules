package de.raindancer.modules.hungergames.model;

import java.time.Duration;
import java.util.Optional;

/**
 * What starts a border phase.
 *
 * <h2>Why either condition, rather than one</h2>
 * A shrink phase after a countdown makes sense; a shrink phase because the field has thinned to a handful
 * of tributes also makes sense, and a server wants both available at once — the phase should fire at
 * twenty minutes <em>or</em> as soon as fewer than four are left, whichever comes first, so a lopsided
 * early game does not leave the survivors wandering a full-size arena. At least one condition has to be
 * set; a trigger with neither would never fire and the phase behind it would silently never happen.
 *
 * @param time       game time since {@link GamePhase#RUNNING} began (virtual time), or empty
 * @param aliveBelow fires as soon as fewer than this many tributes are left, or empty
 */
public record BorderTrigger(Optional<Duration> time, Optional<Integer> aliveBelow) {

    public BorderTrigger {
        if (time.isEmpty() && aliveBelow.isEmpty()) {
            throw new IllegalArgumentException("a BorderTrigger needs at least one condition (time or aliveBelow)");
        }
    }

    public static BorderTrigger atTime(Duration time) {
        return new BorderTrigger(Optional.of(time), Optional.empty());
    }

    public static BorderTrigger aliveBelow(int count) {
        return new BorderTrigger(Optional.empty(), Optional.of(count));
    }

    public static BorderTrigger either(Duration time, int aliveBelow) {
        return new BorderTrigger(Optional.of(time), Optional.of(aliveBelow));
    }

    /** Whether either condition is now satisfied. */
    public boolean isTriggered(Duration elapsed, int aliveCount) {
        boolean byTime = time.map(t -> elapsed.compareTo(t) >= 0).orElse(false);
        boolean byAlive = aliveBelow.map(threshold -> aliveCount < threshold).orElse(false);
        return byTime || byAlive;
    }
}
