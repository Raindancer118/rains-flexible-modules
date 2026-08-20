package de.raindancer.modules.xaeromap.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * An event this module answers.
 *
 * <p>{@code forget} is not optional politeness: this module remembers, per player, which claims that
 * player's client has been told about, and a listener that never forgets grows by one entry for every
 * player who has ever joined. A listener with nothing to forget overrides it empty, which is a decision
 * rather than an oversight.
 */
public interface IXaeroMapListener extends Listener {

    /** That player has gone. Drop whatever was being kept about them. */
    default void forget(UUID player) {
    }
}
