package de.raindancer.modules.essentials.util;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.ui.choose.PlayerDirectory;
import de.raindancer.core.ui.choose.PlayerEntry;
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

    /**
     * Everybody the server has ever seen, for {@code /players} and bare {@code /seen} — a directory
     * to browse rather than a name to type.
     *
     * <p>A player vanished from {@code viewer} is shown exactly as {@link SeenService} already shows
     * one to {@code /seen <name>}: present, but as though they had already logged off at their last
     * real login, rather than dropped from the list entirely. Leaving them out would be a second,
     * unrelated feature riding along on vanish; showing them as online right now would be the leak
     * this whole change is closing.
     */
    public static PlayerDirectory directory(Server server, Vanish vanish, UUID viewer) {
        return new PlayerDirectory(() -> {
            List<PlayerEntry> people = new ArrayList<>();
            if (server == null) {
                return people;
            }
            for (OfflinePlayer person : server.getOfflinePlayers()) {
                String name = person.getName();
                if (name == null) {
                    continue;   // a data file with no name attached is nothing anybody can pick
                }
                boolean visible = viewer == null || vanish.canSee(viewer, person.getUniqueId());
                people.add(new PlayerEntry(person.getUniqueId(), name,
                        person.isOnline() && visible, person.getLastSeen()));
            }
            for (Player who : server.getOnlinePlayers()) {
                // Somebody who has joined but not yet been written to disk is missing above.
                if (people.stream().noneMatch(known -> known.id().equals(who.getUniqueId()))) {
                    boolean visible = viewer == null || vanish.canSee(viewer, who.getUniqueId());
                    people.add(new PlayerEntry(who.getUniqueId(), who.getName(), visible,
                            System.currentTimeMillis()));
                }
            }
            return people;
        }, System::currentTimeMillis);
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
