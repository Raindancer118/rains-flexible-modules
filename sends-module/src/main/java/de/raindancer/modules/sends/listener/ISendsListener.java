package de.raindancer.modules.sends.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/** A listener of this module. {@link #forget} is on the interface so a listener cannot remember players silently. */
public interface ISendsListener extends Listener {

    /** Forget everything remembered about this player. */
    void forget(UUID player);

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
