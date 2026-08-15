package de.raindancer.modules.essentials.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * A listener belonging to this module.
 *
 * <h2>Why {@link #forget} is on the interface</h2>
 * A listener that keeps its own map of players has to be told when somebody leaves, and the one that
 * is not told grows by an entry per player who has ever been on the server.
 */
public interface IEssentialsListener extends Listener {

    /** Forget everything remembered about this player. Empty when this listener remembers nothing. */
    void forget(UUID player);

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
