package de.raindancer.modules.roles.rules;

import de.raindancer.modules.roles.model.ChangeVerdict;
import de.raindancer.modules.roles.model.ChangeVerdict.Reason;
import de.raindancer.modules.roles.model.Choice;

import java.time.Duration;
import java.util.Optional;

/** Whether a player may take a role now: the first freely, then once per wait, staff whenever. */
public final class ChangeRule implements IRolesRule {

    public ChangeVerdict decide(Optional<Choice> current, String wanted, long now, Duration wait, boolean bypass) {
        if (current.isEmpty()) {
            return ChangeVerdict.yes(Reason.FIRST);
        }
        if (current.get().role().equals(wanted)) {
            return ChangeVerdict.no(Reason.SAME, Duration.ZERO);
        }
        if (bypass) {
            return ChangeVerdict.yes(Reason.BYPASS);
        }
        Duration left = left(current, now, wait);
        return left.isZero() ? ChangeVerdict.yes(Reason.WAITED) : ChangeVerdict.no(Reason.WAIT, left);
    }

    /** How long until this player may change; zero when they may now. */
    public Duration left(Optional<Choice> current, long now, Duration wait) {
        if (current.isEmpty() || wait.isZero() || wait.isNegative()) {
            return Duration.ZERO;
        }
        long passed = now - current.get().chosenAt();
        // A clock set back must not lock somebody in for longer than one full wait.
        long left = Math.min(wait.toMillis(), wait.toMillis() - Math.max(0, passed));
        if (passed < 0) {
            left = wait.toMillis();
        }
        return left <= 0 ? Duration.ZERO : Duration.ofMillis(left);
    }

    @Override
    public String describe() {
        return "whether a player may change role now: the first freely, then once per wait";
    }
}
