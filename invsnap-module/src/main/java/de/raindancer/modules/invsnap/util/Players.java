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

    /**
     * Names to complete, online first, then everybody else the server has seen before. Capped, because
     * a four-year-old server has thousands.
     *
     * <p>Offline players matter here as much as online ones: a snapshot is usually taken or restored
     * for somebody who is not currently on, and a suggestion list that only ever offers online names is
     * one that works for every case except the one this command exists for.
     */
    public static List<String> suggestions(Server server, String typed) {
        String wanted = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (server == null) {
            return new ArrayList<>(names);
        }
        for (Player who : server.getOnlinePlayers()) {
            if (who.getName().toLowerCase(Locale.ROOT).startsWith(wanted)) {
                names.add(who.getName());
            }
        }
        for (OfflinePlayer who : server.getOfflinePlayers()) {
            if (names.size() >= 50) {
                break;
            }
            String name = who.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(wanted)) {
                names.add(name);
            }
        }
        List<String> result = new ArrayList<>(names);
        return result.size() > 50 ? result.subList(0, 50) : result;
    }
}
