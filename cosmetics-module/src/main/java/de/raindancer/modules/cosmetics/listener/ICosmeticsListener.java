package de.raindancer.modules.cosmetics.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/** An event listener. One that remembers a player must forget them, or it grows forever. */
public interface ICosmeticsListener extends Listener {

    void forget(UUID player);

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
