package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Server;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Whether this is the island server, and its {@link IslandHost} if so. Asked by plugin name so that nothing of
 * BentoBox's is loaded on a server without it.
 */
public final class IslandHosts {

    private IslandHosts() {
    }

    public static boolean present(Server server) {
        return server.getPluginManager().getPlugin("BentoBox") != null;
    }

    /** @param hidden vanished players, never counted as online */
    public static Optional<IslandHost> on(Server server, LogChannel log, Supplier<String> gameMode,
                                         java.util.function.Predicate<java.util.UUID> hidden) {
        return present(server) ? Optional.of(new BentoIslands(server, log, gameMode, hidden)) : Optional.empty();
    }
}
