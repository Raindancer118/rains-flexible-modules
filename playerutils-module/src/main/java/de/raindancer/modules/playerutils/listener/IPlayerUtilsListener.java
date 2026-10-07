package de.raindancer.modules.playerutils.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/** A listener that remembers somebody must forget them when they leave. */
public interface IPlayerUtilsListener extends Listener {

    void forget(UUID player);

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
