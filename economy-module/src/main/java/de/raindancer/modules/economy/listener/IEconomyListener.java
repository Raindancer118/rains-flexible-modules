package de.raindancer.modules.economy.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/** A listener belonging to this module; {@link #forget} on the interface so nobody is remembered forever. */
public interface IEconomyListener extends Listener {

    void forget(UUID player);

    default String describe() {
        return getClass().getSimpleName();
    }
}
