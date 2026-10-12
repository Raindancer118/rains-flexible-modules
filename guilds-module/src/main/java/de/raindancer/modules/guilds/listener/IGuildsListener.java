package de.raindancer.modules.guilds.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * A listener of this module. One that remembers a player must forget them when they leave, or it grows by an
 * entry for every player who ever joined; one with nothing to forget says so by overriding this empty.
 */
public interface IGuildsListener extends Listener {

    default void forget(UUID player) {
    }
}
