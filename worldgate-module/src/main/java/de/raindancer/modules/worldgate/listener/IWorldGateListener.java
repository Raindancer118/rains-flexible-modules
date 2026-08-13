package de.raindancer.modules.worldgate.listener;

import org.bukkit.event.Listener;

/**
 * A listener belonging to this module.
 *
 * <p>No {@code forget(UUID)} here the way {@code IRtpListener} has one — that exists for a listener
 * that remembers something about a specific player between events, so it can be told when to let go.
 * Nothing in this module does: the portal listener asks the live gate state fresh on every event and
 * keeps nothing of its own between one player crossing a border and the next.
 */
public interface IWorldGateListener extends Listener {

    /** What this listener watches, for a diagnostic. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
