package de.raindancer.modules.moderation.util;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.event.events.FlagEvent;
import de.raindancer.modules.moderation.service.GrimFlags;
import org.bukkit.plugin.Plugin;

/**
 * The one class that touches Grim: it listens to Grim's flags and hands them to {@link GrimFlags}. Loaded only when
 * GrimAC is installed (see {@link #available}), so the plugin runs without it. Never cancels a flag — Grim decides
 * its own setbacks.
 */
public final class GrimBridge {

    /** Grim's plugin name. */
    public static final String PLUGIN = "GrimAC";

    private GrimBridge() {
    }

    public static boolean available(org.bukkit.Server server) {
        Plugin grim = server.getPluginManager().getPlugin(PLUGIN);
        return grim != null && grim.isEnabled();
    }

    /** Starts listening once Grim's API is up. @return what stops it */
    public static Runnable listen(Plugin owner, GrimFlags flags) {
        GrimAPIProvider.getAsync().thenAccept(api -> api.getEventBus().get(FlagEvent.class).onFlagSupplier(owner,
                (user, check, verbose, cancelled) -> {
                    flags.accept(user.getUniqueId(), check.getCheckName(), verbose.get(), check.getViolations(),
                            check.isExperimental());
                    return cancelled;
                }));
        return () -> GrimAPIProvider.getAsync().thenAccept(api -> api.getEventBus().unregisterAllListeners(owner));
    }
}
