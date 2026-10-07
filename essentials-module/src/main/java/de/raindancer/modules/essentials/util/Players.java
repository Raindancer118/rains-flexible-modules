package de.raindancer.modules.essentials.util;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.choose.PlayerDirectory;
import de.raindancer.core.ui.choose.PlayerEntry;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
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
     * Everything that can be typed where a player is wanted: selectors for whoever may use them, visible
     * online names and nicknames, then everybody offline. Offline is always offered so a command can say
     * "they are offline" instead of the typist wondering how a name was spelled.
     */
    public static List<String> suggest(Server server, CommandSender sender, String typed, Vanish vanish) {
        Predicate<Player> visible = visibleTo(vanish, sender instanceof Player viewer ? viewer.getUniqueId() : null);
        // A hidden player is offered exactly as an offline one is, which is what Core does; dropping them here
        // would give them away, because every offline player is offered.
        return PlayerTargets.suggest(server, sender, typed, visible);
    }

    /**
     * Who {@code typed} means, or null after telling {@code sender} why it means nobody usable: selector
     * refused, nothing there, or - when {@code single} - a selector that matched several.
     */
    private static PlayerLookup resolved(Messages messages, Server server, CommandSender sender, String typed,
                                         boolean single, String nobodyKey) {
        return resolved(messages, PlayerTargets.lookup(server, sender, typed), sender, single, nobodyKey);
    }

    private static PlayerLookup resolved(Messages messages, PlayerLookup lookup, CommandSender sender,
                                         boolean single, String nobodyKey) {
        String typed = lookup.typed();
        if (lookup.kind() == PlayerLookup.Kind.SELECTOR_REFUSED) {
            messages.send(sender, "essentials.player.selector-refused", "selector", typed);
            return null;
        }
        if (lookup.isEmpty()) {
            messages.send(sender, nobodyKey, "player", typed);
            return null;
        }
        if (single && lookup.matches().size() > 1) {
            messages.send(sender, "essentials.player.ambiguous", "selector", typed,
                    "count", String.valueOf(lookup.matches().size()));
            return null;
        }
        return lookup;
    }

    /**
     * The online players {@code typed} means - a selector may match many, a name or nickname one - for a
     * command that needs them here. An answer of nobody has already been explained to the sender; an
     * offline player gets "is offline" rather than "never heard of them".
     *
     * @param single what to do with several: refuse them ({@code /msg}) or act on each ({@code /repair})
     */
    public static List<Player> online(Messages messages, Server server, CommandSender sender, String typed,
                                      boolean single, String nobodyKey) {
        PlayerLookup lookup = resolved(messages, server, sender, typed, single, nobodyKey);
        if (lookup == null) {
            return List.of();
        }
        if (lookup.isOfflineOnly()) {
            messages.send(sender, "essentials.player.offline", "player", lookup.typed());
            return List.of();
        }
        return lookup.online();
    }

    /** The one player {@code typed} means, online or not; explains itself when there is not exactly one. */
    public static Optional<OfflinePlayer> one(Messages messages, Server server, CommandSender sender, String typed) {
        PlayerLookup lookup = resolved(messages, server, sender, typed, true, "essentials.no-such-player");
        return lookup == null ? Optional.empty() : lookup.single();
    }

    /** The same for a lookup the caller already made, so a selector is not evaluated twice. */
    public static Optional<OfflinePlayer> one(Messages messages, PlayerLookup lookup, CommandSender sender) {
        PlayerLookup checked = resolved(messages, lookup, sender, true, "essentials.no-such-player");
        return checked == null ? Optional.empty() : checked.single();
    }

    /** Everybody {@code typed} means, online or not - for a command that can sensibly act on several. */
    public static List<OfflinePlayer> any(Messages messages, Server server, CommandSender sender, String typed) {
        PlayerLookup lookup = resolved(messages, server, sender, typed, false, "essentials.no-such-player");
        return lookup == null ? List.of() : lookup.matches();
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
