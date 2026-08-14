package de.raindancer.modules.mannequin.claims;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.extension.ClaimMenuExtension;
import de.raindancer.modules.claims.extension.ClaimMenuExtensions;
import de.raindancer.modules.mannequin.MannequinServices;
import org.bukkit.Bukkit;

/**
 * The whole seam onto {@code claims-module}, and the only place in this module that ever imports one
 * of its classes.
 *
 * <h2>Why this is one class rather than the module reaching in directly</h2>
 * A mannequin belonging to a claim is a genuinely optional feature — unlike {@code chained-module}'s
 * hard dependency on {@code speedrun-module}, this module works exactly as it always has when no claims
 * plugin is installed at all. That only holds if nothing outside this package ever mentions a
 * claims-module type: {@code Claim}, {@code ClaimServices} and the rest are only resolvable when
 * {@code claims-module}'s classes are actually on the classpath, and the JVM only tries to resolve them
 * when a class that mentions them is first loaded. Confined to this package and reached only from
 * behind the try/catch below, that loading — and the {@code NoClassDefFoundError} it would otherwise
 * throw — simply never happens on a server with no claims plugin.
 *
 * <p>The lookup itself goes through Bukkit's own {@code ServicesManager} rather than this reactor's
 * {@code ModuleContext#module(String)}, because that call only ever sees modules hosted in the
 * <em>same</em> plugin — two separate standalone jars, {@code RainsMannequins.jar} beside {@code
 * RainsExtendedClaims.jar}, are exactly the arrangement this has to work for, and {@code
 * ClaimsModule} registers {@code ClaimServices} there for precisely this reason.
 */
public final class ClaimIntegration {

    private ClaimIntegration() {
    }

    /**
     * The real link if a claims plugin is actually running, {@link ClaimLink#NONE} otherwise.
     *
     * <p>Caught broadly rather than narrowly on purpose: a server with no claims plugin at all has
     * none of these classes on its classpath, and exactly what the JVM throws for that
     * ({@code NoClassDefFoundError}, not an {@code Exception}) is an implementation detail nothing
     * outside this method should have to know.
     */
    public static ClaimLink tryLink(LogChannel log) {
        try {
            ClaimServices claims = Bukkit.getServicesManager().load(ClaimServices.class);
            if (claims == null) {
                return ClaimLink.NONE;
            }
            log.info("Claims integration is active: mannequins can belong to a claim.");
            return new MannequinClaimLink(claims);
        } catch (Throwable notInstalled) {
            return ClaimLink.NONE;
        }
    }

    /**
     * Puts this module's "Mannequins" button on {@code ClaimMenu}, if claims is actually there.
     *
     * @return how to take the button down again, or {@code null} when there was nothing to register
     */
    public static AutoCloseable tryRegisterMenu(MannequinServices services, LogChannel log) {
        try {
            if (Bukkit.getServicesManager().load(ClaimServices.class) == null) {
                return null;
            }
            ClaimMenuExtension extension = new MannequinClaimMenuExtension(services);
            ClaimMenuExtensions.register(extension);
            return () -> ClaimMenuExtensions.unregister(extension);
        } catch (Throwable notInstalled) {
            return null;
        }
    }
}
