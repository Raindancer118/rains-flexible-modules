package de.raindancer.modules.anticheat.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/** An event listener of this module; told when a player leaves so it can let go of them. */
public interface IAntiCheatListener extends Listener {

    default void forget(UUID player) {
    }

    String describe();
}
