package de.raindancer.modules.farmworld.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * A listener belonging to this module.
 *
 * <p>Extends Bukkit's {@link Listener} and adds what the module needs of one: registered through the
 * module's context so it is unregistered again when the module stops, and holding
 * {@code FarmWorldServices} rather than reaching for anything.
 *
 * <h2>Why {@link #forget} is on the interface</h2>
 * A listener that keeps its own map of players has to be told when somebody leaves, and the one that is
 * not told grows by an entry per player who has ever been on the server. That has already happened twice
 * in this repository, which is why it is a method that cannot be omitted quietly rather than a paragraph
 * in a readme.
 */
public interface IFarmWorldListener extends Listener {

    /**
     * Forget everything remembered about this player.
     *
     * <p>A listener that remembers nothing may leave this empty; one that does must not.
     */
    void forget(UUID player);

    /** What this listener watches, for the diagnostic that lists what is registered. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
