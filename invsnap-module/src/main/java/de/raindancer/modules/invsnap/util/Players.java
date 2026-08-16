package de.raindancer.modules.invsnap.util;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turning what somebody typed into somebody.
 *
 * <p>The same lookup {@code essentials-module}'s own {@code Players} keeps, kept here rather than
 * shared because the two modules do not depend on each other and a three-line helper is not worth
 * a dependency for. Genuinely generic, hence {@code util}.
 */
public final class Players {

    private Players() {
    }

    /** Somebody the server has actually seen, online or not — never a cache miss silently made up. */
    public static Optional<OfflinePlayer> find(Server server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        Player online = server.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online);
        }
        return Optional.ofNullable(server.getOfflinePlayerIfCached(name));
    }

    /** Names to complete, online first. Capped, because a four-year-old server has thousands. */
    public static List<String> suggestions(Server server, String typed) {
        String wanted = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        if (server == null) {
            return names;
        }
        for (Player who : server.getOnlinePlayers()) {
            if (who.getName().toLowerCase(Locale.ROOT).startsWith(wanted)) {
                names.add(who.getName());
            }
        }
        return names.size() > 50 ? names.subList(0, 50) : names;
    }
}
