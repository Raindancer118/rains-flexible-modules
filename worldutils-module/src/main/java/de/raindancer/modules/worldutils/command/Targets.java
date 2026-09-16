package de.raindancer.modules.worldutils.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.worldutils.WorldUtilsServices;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * Who a {@code /w} or {@code /dim} is about: the sender, or whoever a name or selector means — Core's
 * {@link PlayerTargets} reads those, so {@code @a[distance=..10]} means here what it means to vanilla.
 * Each refusal is said, since a command that silently moves nobody is typed four more times.
 */
final class Targets {

    private Targets() {
    }

    /** @return empty when a refusal has already been sent */
    static Optional<List<Player>> of(WorldUtilsServices live, CommandSender sender, String[] args, int at,
                                     String othersNode) {
        if (args.length <= at) {
            if (sender instanceof Player player) {
                return Optional.of(List.of(player));
            }
            live.messages().send(sender, "worldutils.console-needs-a-player");
            return Optional.empty();
        }
        if (!sender.hasPermission(othersNode)) {
            live.messages().send(sender, "worldutils.no-permission-others");
            return Optional.empty();
        }
        List<Player> found = PlayerTargets.resolve(live.server(), sender, args[at]);
        if (found.isEmpty()) {
            live.messages().send(sender, "worldutils.nobody-matched", "value", args[at]);
            return Optional.empty();
        }
        if (found.size() > 1 || !found.getFirst().equals(sender)) {
            live.messages().send(sender, "worldutils.sending", "count", found.size());
        }
        return Optional.of(found);
    }
}
