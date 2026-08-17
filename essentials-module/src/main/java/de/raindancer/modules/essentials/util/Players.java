package de.raindancer.modules.essentials.util;

import de.raindancer.core.moderation.vanish.Vanish;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Turning what somebody typed into somebody.
 *
 * <p>Genuinely generic, hence {@code util}: the same lookup moderation-module keeps under its own
 * name, kept here rather than shared because the two modules do not depend on each other and a
 * three-line helper is not worth a dependency for.
 */
public final class Players {

    private Players() {
    }

    /** Somebody the server has actually seen, online or not. */
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

    /** What to call somebody in a message, given that a name is the one thing that can be missing. */
    public static String nameOf(OfflinePlayer who) {
        if (who == null) {
            return "somebody";
        }
        String name = who.getName();
        return name == null || name.isBlank() ? who.getUniqueId().toString() : name;
    }

    /**
     * Names to complete, online first, whoever asked can actually see. Capped, because a
     * four-year-old server has thousands.
     *
     * <p>Filters out a vanished player from anybody who is not allowed to see them — a moderator's
     * name completing in a tab-complete list is exactly as much of a giveaway as one appearing in
     * {@code /list}, and easier to miss reviewing for.
     */
    public static List<String> suggestions(Server server, String typed, Vanish vanish, UUID viewer) {
        String wanted = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        if (server == null) {
            return names;
        }
        for (Player who : server.getOnlinePlayers()) {
            if (viewer != null && !vanish.canSee(viewer, who.getUniqueId())) {
                continue;
            }
            if (who.getName().toLowerCase(Locale.ROOT).startsWith(wanted)) {
                names.add(who.getName());
            }
        }
        return names.size() > 50 ? names.subList(0, 50) : names;
    }

    /**
     * The same, for whoever is not a player and so has nobody to hide from — the console, which
     * already sees everything the server does.
     */
    public static List<String> suggestions(Server server, String typed, Vanish vanish) {
        return suggestions(server, typed, vanish, null);
    }

    /** Whether a real player, online or previously seen, already answers to this exact name. */
    public static boolean realNameInUse(Server server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return false;
        }
        for (Player online : server.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return find(server, name).isPresent();
    }
}
