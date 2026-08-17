package de.raindancer.modules.wallsroads.claims;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.claims.ClaimServices;
import org.bukkit.Bukkit;

/**
 * The whole seam onto {@code claims-module}, and the only place in this module that ever imports one
 * of its classes — see {@code mannequin-module}'s own {@code ClaimIntegration} for the full
 * reasoning; this is the same shape, one lookup narrower.
 */
public final class ClaimIntegration {

    private ClaimIntegration() {
    }

    public static ClaimLink tryLink(LogChannel log) {
        try {
            ClaimServices claims = Bukkit.getServicesManager().load(ClaimServices.class);
            if (claims == null) {
                return ClaimLink.NONE;
            }
            log.info("Claims integration is active: a road can route to a claim's entrance.");
            return new WallsRoadsClaimLink(claims);
        } catch (Throwable notInstalled) {
            return ClaimLink.NONE;
        }
    }
}
