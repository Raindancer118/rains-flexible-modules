package de.raindancer.modules.performance.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/** A listener belonging to this module, told when a player leaves so it can drop what it holds for them. */
public interface IPerformanceListener extends Listener {

    void forget(UUID player);

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
