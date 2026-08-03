package de.raindancer.modules.claims.listener;

import org.bukkit.event.Listener;

/**
 * A listener belonging to this module.
 *
 * <p>Extends Bukkit's {@link Listener} and adds what the module needs of one: registered through the module's
 * context so it is unregistered again when the module stops, and holding nothing but {@code ClaimServices}.
 *
 * <h2>Why the second part matters</h2>
 * A listener that keeps its own map of players is a listener that has to be told when somebody leaves, and the
 * one that is not told grows by an entry per player who has ever been on the server. That has already happened
 * twice here. {@link #forget} is on the interface so it cannot be omitted quietly — a listener with nothing to
 * forget overrides it with an empty body and says so, which is a decision rather than an oversight.
 */
public interface IClaimListener extends Listener {

    /**
     * Forget everything remembered about this player.
     *
     * <p>Called from the session listener when they leave. A listener that remembers nothing may leave this
     * empty; one that does must not.
     */
    default void forget(java.util.UUID player) {
        // Nothing remembered.
    }

    /** What this listener watches, for the diagnostic that lists what is registered. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
