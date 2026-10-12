package de.raindancer.modules.arenagate.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/** A listener of this module. {@link #forget} is on the interface so a listener cannot remember players silently. */
public interface IGateListener extends Listener {

    void forget(UUID player);
}
