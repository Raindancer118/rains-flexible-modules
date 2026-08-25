package de.raindancer.modules.wallsroads.map;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.xaeromap.XaeroMapServices;
import org.bukkit.Bukkit;

/**
 * The whole seam onto {@code xaeromap-module}, and the only place in this module that imports one of
 * its classes — the same shape as {@code claims.ClaimIntegration}.
 */
public final class MapIntegration {

    private MapIntegration() {
    }

    public static MapLink tryLink(LogChannel log) {
        try {
            XaeroMapServices map = Bukkit.getServicesManager().load(XaeroMapServices.class);
            if (map == null) {
                return MapLink.NONE;
            }
            log.info("Map integration is active: gates and road ends can be sent to a player's map.");
            return new XaeroMapLink(map);
        } catch (Throwable notInstalled) {
            return MapLink.NONE;
        }
    }
}
