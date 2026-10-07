package de.raindancer.modules.cosmetics.command;

import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Whom a name, a nickname or a selector means for something that is kept by id: online or not, one or many.
 * A refusal is said here, so the caller only has to stop when the list is empty.
 */
final class Targets {

    private Targets() {
    }

    static List<OfflinePlayer> anybody(Server server, Messages messages, CommandSender sender, String text) {
        PlayerLookup found = PlayerTargets.lookup(server, sender, text);
        if (found.kind() == PlayerLookup.Kind.SELECTOR_REFUSED) {
            messages.send(sender, "cosmetics.selector-refused", "selector", text);
        } else if (found.isEmpty()) {
            if (found.kind() == PlayerLookup.Kind.SELECTOR) {
                messages.send(sender, "cosmetics.selector-nobody", "selector", text);
            } else {
                messages.send(sender, "cosmetics.unknown-player", "player", text);
            }
        }
        return found.matches();
    }
}
