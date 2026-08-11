package de.raindancer.modules.rtp.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * A listener belonging to this module.
 *
 * <h2>Why {@link #forget} is on the interface</h2>
 * A listener that remembers a player has to be told when they leave, or it grows by an entry for
 * every player who has ever been on the server. Overriding it empty is a decision rather than an
 * oversight, and it cannot be omitted quietly.
 */
public interface IRtpListener extends Listener {

    /** Forget everything remembered about this player. */
    void forget(UUID player);

    /** What this listener watches, for a diagnostic. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
