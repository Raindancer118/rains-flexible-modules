package de.raindancer.modules.enchants.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

public interface IEnchantsListener extends Listener {

    /** Called when a player leaves. A listener with nothing to forget overrides it empty. */
    default void forget(UUID player) {
    }
}
