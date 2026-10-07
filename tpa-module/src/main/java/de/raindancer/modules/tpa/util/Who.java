package de.raindancer.modules.tpa.util;

import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Turns typed text into exactly one player and, when it cannot, says which of the five reasons it was —
 * "nobody", "offline", "too many", "selectors are not yours", "that selector found nobody" are five
 * different things to do something about.
 */
public final class Who {

    private Who() {
    }

    /** The one online player {@code text} means, or empty after the sender has been told why not. */
    public static Optional<Player> online(Server server, Messages messages, CommandSender sender, String text) {
        PlayerLookup found = PlayerTargets.lookup(server, sender, text);
        if (!isOne(messages, sender, found)) {
            return Optional.empty();
        }
        if (found.isOfflineOnly()) {
            messages.send(sender, "tpa.is-offline", "player", PlayerTargets.shownName(found.matches().getFirst()));
            return Optional.empty();
        }
        return found.singleOnline();
    }

    /** The one player {@code text} means, here or not, or empty after the sender has been told why not. */
    public static Optional<OfflinePlayer> anybody(Server server, Messages messages, CommandSender sender, String text) {
        PlayerLookup found = PlayerTargets.lookup(server, sender, text);
        return isOne(messages, sender, found) ? found.single() : Optional.empty();
    }

    private static boolean isOne(Messages messages, CommandSender sender, PlayerLookup found) {
        switch (found.kind()) {
            case SELECTOR_REFUSED -> messages.send(sender, "tpa.selector-refused", "selector", found.typed());
            case SELECTOR -> {
                if (found.isEmpty()) {
                    messages.send(sender, "tpa.selector-nobody", "selector", found.typed());
                } else if (found.matches().size() > 1) {
                    messages.send(sender, "tpa.too-many", "selector", found.typed(),
                            "count", String.valueOf(found.matches().size()));
                } else {
                    return true;
                }
            }
            default -> {
                if (found.isEmpty()) {
                    messages.send(sender, "tpa.no-such-player", "player", found.typed());
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * What to complete: selectors for those who may, everybody offline, and the online players
     * {@code visible} lets through.
     *
     * <p>A hidden player is offered as the offline player everybody else sees — never as online, never
     * missing, since either would give them away.
     */
    public static java.util.List<String> suggest(Server server, CommandSender sender, String typed,
                                                 java.util.function.Predicate<Player> visible) {
        // A hidden player is offered exactly as an offline one is, which is what Core does; dropping them here
        // would give them away, because every offline player is offered.
        java.util.List<String> offered = new java.util.ArrayList<>(PlayerTargets.suggest(server, sender, typed, visible));
        // Never yourself — by name or by your own nickname. Not about vanish: a request to yourself is pointless.
        if (sender instanceof Player self) {
            offered.removeIf(entry -> PlayerTargets.lookup(server, null, entry).single()
                    .map(who -> who.getUniqueId().equals(self.getUniqueId())).orElse(false));
        }
        return offered;
    }
}
