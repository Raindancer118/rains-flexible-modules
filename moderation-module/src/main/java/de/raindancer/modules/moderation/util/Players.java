package de.raindancer.modules.moderation.util;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
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

/**
 * Turning what somebody typed into somebody.
 *
 * <h2>Why offline players count</h2>
 * Because the whole point of a ban command is usually that they are not here. A resolver that only
 * finds online players is one that works for every case except the one it exists for.
 *
 * <h2>Why a name nobody has used gives nothing back</h2>
 * {@code Bukkit.getOfflinePlayer(String)} happily invents a profile for a name the server has never
 * seen, with a made-up id. Banning that is a ban nobody can lift and a record nothing can find — and
 * the typo that produced it looks exactly like a success. So this asks for a <em>cached</em> profile
 * and answers empty when there is none.
 *
 * <p>Genuinely generic, hence {@code util}: nothing here is about moderation, and the ordering,
 * searching and sectioning of a list of players is Core's {@code PlayerDirectory}, not this.
 */
public final class Players {

    private Players() {
    }

    /** Somebody the server has actually seen, online or not, by real name or nickname. */
    public static Optional<OfflinePlayer> find(Server server, String name) {
        return PlayerTargets.find(server, name);
    }

    /**
     * Who {@code typed} means - a selector, a real name or a nickname, online or not - when that is exactly
     * one player; otherwise says why not ("selectors are not yours", "nobody", "matches N") and answers empty.
     * Never several: what this feeds is a ban, a rank or a report, and {@code /ban @a} is not a thing to guess at.
     */
    public static Optional<OfflinePlayer> one(Messages messages, Server server, CommandSender sender, String typed) {
        return one(messages, PlayerTargets.lookup(server, sender, typed), sender);
    }

    public static Optional<OfflinePlayer> one(Messages messages, PlayerLookup lookup, CommandSender sender) {
        if (lookup.kind() == PlayerLookup.Kind.SELECTOR_REFUSED) {
            messages.send(sender, "moderation.player.selector-refused", "selector", lookup.typed());
        } else if (lookup.isEmpty()) {
            messages.send(sender, "moderation.no-such-player", "player", lookup.typed());
        } else if (lookup.matches().size() > 1) {
            messages.send(sender, "moderation.player.ambiguous", "selector", lookup.typed(),
                    "count", String.valueOf(lookup.matches().size()));
        } else {
            return lookup.single();
        }
        return Optional.empty();
    }

    /**
     * Everything that can be typed where a player is wanted, for staff: selectors if the sender may use
     * them, then names and nicknames, online first and offline always (a ban is usually for somebody who
     * is not here). Staff see everybody, vanished included.
     */
    public static List<String> suggest(Server server, CommandSender sender, String typed) {
        return PlayerTargets.suggest(server, sender, typed, who -> true);
    }

    /**
     * The same for a player typing {@code /report}: somebody vanished from them is offered exactly as an
     * offline player is — never as online, never missing.
     */
    public static List<String> suggest(Server server, CommandSender sender, String typed, Vanish vanish) {
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        java.util.function.Predicate<Player> visible = who -> viewer == null || vanish.canSee(viewer, who.getUniqueId());
        // A hidden player is offered exactly as an offline one is, which is what Core does; dropping them here
        // would give them away, because every offline player is offered.
        return PlayerTargets.suggest(server, sender, typed, visible);
    }

    /** Their id, when the server has seen them. */
    public static Optional<UUID> idOf(Server server, String name) {
        return PlayerTargets.idOf(server, name);
    }

    /** What to call somebody in a message, given that a name is the one thing that can be missing. */
    public static String nameOf(OfflinePlayer who) {
        if (who == null) {
            return "somebody";
        }
        String name = who.getName();
        return name == null || name.isBlank() ? who.getUniqueId().toString() : name;
    }

    /** The same, by id, for a record about somebody the server may not remember. */
    public static String nameOf(Server server, UUID who) {
        if (server == null || who == null) {
            return "somebody";
        }
        Player online = server.getPlayer(who);
        return online != null ? online.getName() : nameOf(server.getOfflinePlayer(who));
    }

    /**
     * Everybody the server knows, as Core's directory wants them.
     *
     * <p>Reads the player data directory, so it is built when a chooser opens rather than held: a
     * directory captured at startup does not contain the player who joined this evening.
     */
    public static List<PlayerEntry> everybody(Server server) {
        return everybody(server, java.util.Set.of());
    }

    /**
     * The same, with somebody counted as offline however present they are.
     *
     * <p>What vanish needs. Hiding a player's entity does not hide them from a list a plugin builds
     * itself, and a chooser that shows a vanished moderator as online is the one place anybody would
     * look to check. Marked offline rather than left out entirely: they are still pickable — a moderator
     * still has to be able to open the page of somebody who happens to be hidden — they simply do not
     * read as being here.
     *
     * @param hidden who to show as offline; their last-seen is left as it was, so they sort where they
     *               would have if they really had logged off
     */
    public static List<PlayerEntry> everybody(Server server, java.util.Set<java.util.UUID> hidden) {
        List<PlayerEntry> everyone = new ArrayList<>();
        if (server == null) {
            return everyone;
        }
        java.util.Set<java.util.UUID> outOfSight = hidden == null ? java.util.Set.of() : hidden;
        for (OfflinePlayer who : server.getOfflinePlayers()) {
            String name = who.getName();
            if (name == null || name.isBlank()) {
                continue;   // a data file with no name attached is nothing anybody can pick
            }
            boolean online = who.isOnline() && !outOfSight.contains(who.getUniqueId());
            everyone.add(new PlayerEntry(who.getUniqueId(), name, online, who.getLastSeen()));
        }
        for (Player who : server.getOnlinePlayers()) {
            if (everyone.stream().noneMatch(entry -> entry.id().equals(who.getUniqueId()))) {
                everyone.add(new PlayerEntry(who.getUniqueId(), who.getName(),
                        !outOfSight.contains(who.getUniqueId()), System.currentTimeMillis()));
            }
        }
        return everyone;
    }

    /**
     * Names and nicknames to complete, online first, then everybody else the server has seen. Offline
     * players matter as much as online ones: {@code /promote}, {@code /ban} and the rest are usually
     * aimed at somebody who is not currently on.
     */
    public static List<String> suggestions(Server server, String typed) {
        return PlayerTargets.suggestKnown(server, typed, who -> true);
    }

    /**
     * The same, for a caller who is not staff and so must not be handed a vanished name to complete —
     * {@code /report}'s tab-complete is the one place in this module a plain player reaches this list.
     * Offline players are not hidden by vanish: there is no live entity to hide. A vanished player's
     * nickname is offered like any offline player's — leaving it out would be what gave them away.
     */
    public static List<String> suggestions(Server server, String typed, Vanish vanish, UUID viewer) {
        // A hidden player is offered exactly as an offline one is, which is what Core does; dropping them here
        // would give them away, because every offline player is offered.
        return PlayerTargets.suggestKnown(server, typed, who -> vanish.canSee(viewer, who.getUniqueId()));
    }
}
