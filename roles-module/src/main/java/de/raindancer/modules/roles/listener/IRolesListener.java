package de.raindancer.modules.roles.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/** A listener belonging to this module; {@link #forget} so nobody is remembered after they leave. */
public interface IRolesListener extends Listener {

    void forget(UUID player);
}
