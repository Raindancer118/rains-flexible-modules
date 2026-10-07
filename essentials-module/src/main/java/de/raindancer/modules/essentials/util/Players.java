package de.raindancer.modules.essentials.util;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.choose.PlayerDirectory;
import de.raindancer.core.ui.choose.PlayerEntry;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Turning what somebody typed into somebody — by real name or by nickname.
 *
 * <p>All of it is Core's {@link PlayerTargets}, which knows the nickname directory; what stays here is
 * the part specific to this module: who may be shown to whom, given vanish.
 */
public final class Players {

    private Players() {
    }

    /** Somebody the server has actually seen, online or not — by name, or by nickname. */
    public static Optional<OfflinePlayer> find(Server server, String name) {
        return PlayerTargets.find(server, name);
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
     * Which online players {@code viewer} may know are there. A vanished player is given away just as
     * much by a nickname in a tab-complete list as by a name, so every suggestion goes through this.
     */
    public static Predicate<Player> visibleTo(Vanish vanish, UUID viewer) {
        return who -> viewer == null || vanish.canSee(viewer, who.getUniqueId());
    }

    /**
     * Names and nicknames to complete for a command that may be aimed at somebody who is not here:
     * online first, then everybody known. Capped by Core.
     */
    public static List<String> suggestions(Server server, String typed, Vanish vanish, UUID viewer) {
        return PlayerTargets.suggestKnown(server, typed, visibleTo(vanish, viewer));
    }

    /** The same, for whoever is not a player and so has nobody to hide from. */
    public static List<String> suggestions(Server server, String typed, Vanish vanish) {
        return suggestions(server, typed, vanish, null);
    }

    /**
     * Online players only, for a command that needs somebody here ({@code /msg}). Selectors are left out
     * of the list: they work in other commands, but offering {@code @a} to a private message is a trap.
     */
    public static List<String> onlineSuggestions(Server server, String typed, Vanish vanish, UUID viewer) {
        return PlayerTargets.suggest(server, typed, visibleTo(vanish, viewer)).stream()
                .filter(name -> !PlayerTargets.isSelector(name))
                .toList();
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
        return PlayerTargets.isRealName(server, name);
    }
}
