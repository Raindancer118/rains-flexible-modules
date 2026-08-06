package de.raindancer.modules.hungergames.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * A listener belonging to this module.
 *
 * <p>Extends Bukkit's {@link Listener} and adds what the module needs of one: registered through the module's
 * context so it is unregistered again when the module stops, and holding {@code HungerGamesServices} rather
 * than reaching for anything.
 *
 * <h2>Why {@link #forget} is on the interface</h2>
 * A listener that keeps its own map of players has to be told when somebody leaves, and the one that is not
 * told grows by an entry per player who has ever been on the server. That has already happened twice in this
 * repository, which is why it is a method that cannot be omitted quietly rather than a paragraph in a readme.
 *
 * <h2>The one thing this module has to be careful about</h2>
 * <b>Forgetting a player is not eliminating them.</b> A tribute who disconnects stays ALIVE until something
 * eliminates them — that is the invariant the whole winner logic rests on, and it is why somebody can rejoin
 * mid-round and still be in the game. So {@code forget} drops what a <em>listener</em> is caching about
 * somebody: a pending countdown, a hit window, an open input. It never touches the registry.
 */
public interface IHungerGamesListener extends Listener {

    /**
     * Forget everything this listener is remembering about this player.
     *
     * <p>A listener that remembers nothing may leave this empty; one that does must not. Never a route to
     * eliminating anybody — see the class note.
     */
    void forget(UUID player);

    /** What this listener watches, for the diagnostic that lists what is registered. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
