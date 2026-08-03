package de.raindancer.modules.moderation.util;

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

    /** Their id, when the server has seen them. */
    public static Optional<UUID> idOf(Server server, String name) {
        return find(server, name).map(OfflinePlayer::getUniqueId);
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
        List<PlayerEntry> everyone = new ArrayList<>();
        if (server == null) {
            return everyone;
        }
        for (OfflinePlayer who : server.getOfflinePlayers()) {
            String name = who.getName();
            if (name == null || name.isBlank()) {
                continue;   // a data file with no name attached is nothing anybody can pick
            }
            everyone.add(new PlayerEntry(who.getUniqueId(), name, who.isOnline(),
                    who.getLastSeen()));
        }
        for (Player who : server.getOnlinePlayers()) {
            if (everyone.stream().noneMatch(entry -> entry.id().equals(who.getUniqueId()))) {
                everyone.add(new PlayerEntry(who.getUniqueId(), who.getName(), true,
                        System.currentTimeMillis()));
            }
        }
        return everyone;
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
