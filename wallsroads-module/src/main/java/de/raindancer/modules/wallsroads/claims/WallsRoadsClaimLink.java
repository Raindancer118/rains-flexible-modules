package de.raindancer.modules.wallsroads.claims;

import de.raindancer.modules.claims.ClaimServices;
import org.bukkit.Location;

import java.util.Optional;

/** The real {@link ClaimLink}, once a claims plugin is actually running. */
final class WallsRoadsClaimLink implements ClaimLink {

    private final ClaimServices claims;

    WallsRoadsClaimLink(ClaimServices claims) {
        this.claims = claims;
    }

    @Override
    public Optional<Location> entranceOf(String claimName) {
        return claims.entranceOf(claimName);
    }
}
