package de.raindancer.modules.worldutils.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * A listener belonging to this module.
 *
 * <p>{@code forget(UUID)} is here because a listener that remembers a player must be told when they
 * leave. Neither listener in this module holds anything per player in memory — last positions live in
 * Core's place store — so both override it empty, which is a decision rather than an oversight.
 */
public interface IWorldUtilsListener extends Listener {

    void forget(UUID player);

    /** What this listener watches, for a diagnostic. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
