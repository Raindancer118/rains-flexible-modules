package de.raindancer.modules.wallsroads.claims;

import org.bukkit.Location;

import java.util.Optional;

/** What this module can ask claims-module for. {@link #NONE} on a server with no claims plugin. */
public interface ClaimLink {

    ClaimLink NONE = claimName -> Optional.empty();

    /** A claim's entrance, by name — where a road wants to end if it is meant to reach that claim. */
    Optional<Location> entranceOf(String claimName);
}
