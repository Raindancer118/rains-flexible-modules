package de.raindancer.modules.hungergames.store;

import java.util.Optional;
import java.util.UUID;

/**
 * Asked by {@link GameSession#register} before somebody becomes a tribute, so a fee can be taken first.
 *
 * <p>A port, like {@link GameEvents}: the session stays free of money and of Bukkit.
 */
public interface EntryGate {

    /** The gate that lets everybody in, for a server with no entry fee. */
    EntryGate OPEN = (uuid, name) -> Optional.empty();

    /** @return why this tribute may not register, or empty when they may (any fee is then already taken) */
    Optional<String> admit(UUID uuid, String name);
}
