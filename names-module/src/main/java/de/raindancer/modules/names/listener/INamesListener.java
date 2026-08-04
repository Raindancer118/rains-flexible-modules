package de.raindancer.modules.names.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * A listener belonging to this module.
 *
 * <p>Extends Bukkit's {@link Listener} and adds what the module needs of one: registered through the
 * module's context so it is unregistered again when the module stops, and holding
 * {@code NamesServices} rather than reaching for anything.
 *
 * <h2>Why {@link #forget} is on the interface</h2>
 * A listener that keeps its own map of players has to be told when somebody leaves, and the one that is
 * not told grows by an entry per player who has ever been on the server. That has already happened twice
 * in this repository.
 *
 * <p>So it is on the interface and cannot be omitted quietly. Every listener here remembers nothing —
 * the state lives on the items and in the grid — and each of them overrides this with an empty body and
 * says so, which is a decision rather than an oversight.
 */
public interface INamesListener extends Listener {

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
