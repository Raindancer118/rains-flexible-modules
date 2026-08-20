package de.raindancer.modules.xaeromap.claims;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.claims.ClaimServices;
import org.bukkit.Bukkit;
import org.bukkit.Server;

/**
 * The whole seam onto {@code claims-module}, and the only place besides {@link ClaimsModuleSource} that
 * ever names one of its classes.
 *
 * <p>Same shape as {@code mannequin-module}'s integration of the same module, and for the same reason:
 * claims-module's classes only exist on the classpath when a claims plugin is actually installed, and
 * the JVM only tries to resolve them when a class that mentions them is first loaded. Kept to this
 * package and reached only from behind the catch below, that load — and the
 * {@code NoClassDefFoundError} it would otherwise throw — never happens.
 *
 * <p>The lookup goes through Bukkit's {@code ServicesManager} rather than {@code
 * ModuleContext#module(String)}, because that call only sees modules hosted in the <em>same</em> plugin,
 * and {@code RainsXaeroMap.jar} beside {@code RainsExtendedClaims.jar} is exactly the arrangement this
 * has to work in.
 */
public final class ClaimIntegration {

    private ClaimIntegration() {
    }

    /** The real source if a claims plugin is running, {@link ClaimSource#NONE} otherwise. */
    public static ClaimSource trySource(LogChannel log) {
        return trySource(Bukkit.getServer(), log);
    }

    /**
     * The same, against a given server.
     *
     * <p>Caught broadly rather than narrowly on purpose: what the JVM throws for a class that is not
     * there is an {@code Error}, not an {@code Exception}, and which one it is is an implementation
     * detail nothing outside this method should have to know.
     */
    public static ClaimSource trySource(Server server, LogChannel log) {
        try {
            ClaimServices claims = server == null ? null
                    : server.getServicesManager().load(ClaimServices.class);
            if (claims == null) {
                if (log != null) {
                    log.info("No claims plugin is registered, so nothing is drawn on the map yet — "
                            + "every world still gets its own map.");
                }
                return ClaimSource.NONE;
            }
            ClaimSource source = new ClaimsModuleSource(claims, server);
            if (log != null) {
                log.info("Claims are coming from {}.", source.name());
            }
            return source;
        } catch (Throwable notInstalled) {
            return ClaimSource.NONE;
        }
    }
}
