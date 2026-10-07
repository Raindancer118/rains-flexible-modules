package de.raindancer.modules.claims.util;

import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Turns what was typed into one player for a claim command, and says why when it cannot — "nobody",
 * "offline", "too many" and "selectors are not yours" are four different things to do something about.
 *
 * <p>Always through {@link PlayerTargets}, so a real name wins over somebody's nickname: a claim that
 * trusts, bans or is handed to "Steve" must go to the Steve who exists, never to whoever took the nickname.
 */
public final class Subjects {

    private Subjects() {
    }

    /** The uuid of the one player {@code text} means, here or not. */
    public static Optional<UUID> one(Server server, Messages messages, CommandSender sender, String text) {
        return found(server, messages, sender, text).flatMap(lookup -> lookup.single())
                .map(who -> who.getUniqueId());
    }

    /** The one player {@code text} means, who has to be online. */
    public static Optional<Player> online(Server server, Messages messages, CommandSender sender, String text) {
        Optional<PlayerLookup> lookup = found(server, messages, sender, text);
        if (lookup.isPresent() && lookup.get().isOfflineOnly()) {
            messages.send(sender, "error.player-offline", "player",
                    PlayerTargets.shownName(lookup.get().matches().getFirst()));
            return Optional.empty();
        }
        return lookup.flatMap(PlayerLookup::singleOnline);
    }

    private static Optional<PlayerLookup> found(Server server, Messages messages, CommandSender sender, String text) {
        PlayerLookup lookup = PlayerTargets.lookup(server, sender, text);
        if (lookup.kind() == PlayerLookup.Kind.SELECTOR_REFUSED) {
            messages.send(sender, "error.selector-refused", "selector", text);
        } else if (lookup.isEmpty()) {
            if (lookup.kind() == PlayerLookup.Kind.SELECTOR) {
                messages.send(sender, "error.selector-nobody", "selector", text);
            } else {
                messages.send(sender, "error.no-such-player", "player", text);
            }
        } else if (lookup.matches().size() > 1) {
            messages.send(sender, "error.too-many-players", "selector", text,
                    "count", String.valueOf(lookup.matches().size()));
        } else {
            return Optional.of(lookup);
        }
        return Optional.empty();
    }

    /**
     * What to complete: selectors for those who may use them, everybody offline, and the online players
     * {@code visible} lets through; a hidden player is offered as the offline player everybody else sees.
     */
    public static List<String> suggest(Server server, CommandSender sender, String typed, Predicate<Player> visible) {
        // A hidden player is offered exactly as an offline one is, which is what Core does; dropping them here
        // would give them away, because every offline player is offered.
        return PlayerTargets.suggest(server, sender, typed, visible);
    }
}
