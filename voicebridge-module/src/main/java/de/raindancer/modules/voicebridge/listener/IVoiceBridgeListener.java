package de.raindancer.modules.voicebridge.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * A listener belonging to this module. {@link #forget} is on the interface because a listener that
 * remembers a player has to be told when they leave.
 */
public interface IVoiceBridgeListener extends Listener {

    void forget(UUID player);

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
