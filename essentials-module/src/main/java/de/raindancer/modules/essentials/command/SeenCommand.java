package de.raindancer.modules.essentials.command;

import de.raindancer.core.world.time.Times;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import de.raindancer.modules.essentials.util.SeenService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** {@code /seen <player>} — when somebody was last here, and for how long they have played. */
public final class SeenCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public SeenCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "when somebody was last here, and for how long they have played";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            live.messages().send(sender, "essentials.usage", "usage", "/seen <player>");
            return;
        }
        Optional<OfflinePlayer> found = Players.find(live.server(), args[0]);
        if (found.isEmpty()) {
            live.messages().send(sender, "essentials.no-such-player", "player", args[0]);
            return;
        }
        OfflinePlayer them = found.get();
        String name = Players.nameOf(them);
        SeenService.Seen seen = SeenService.of(them);

        if (!seen.everJoined()) {
            live.messages().send(sender, "essentials.seen.never", "player", name);
            return;
        }
        if (seen.online()) {
            live.messages().send(sender, "essentials.seen.online", "player", name,
                    "playtime", Times.describe(seen.playtime()));
            return;
        }
        Instant lastSeen = seen.lastSeen().orElse(Instant.EPOCH);
        String ago = Times.describe(Duration.between(lastSeen, Instant.now()));
        live.messages().send(sender, "essentials.seen.offline", "player", name,
                "ago", ago, "playtime", Times.describe(seen.playtime()));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return Players.suggestions(services.get().server(), args.length == 1 ? args[0] : "");
        }
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.SEEN;
    }
}
