package de.raindancer.modules.essentials.command;

import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import de.raindancer.modules.essentials.util.SeenService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /seen <player>} — when somebody was last here, and for how long they have played. Bare
 * {@code /seen} opens {@link PlayerChooser} instead of a usage line — the same door
 * {@code /players} uses, since picking somebody from a list and being told the same thing this
 * command already says about a name typed directly are the same feature from two entrances.
 */
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
            if (!(sender instanceof Player viewer)) {
                live.messages().send(sender, "essentials.usage", "usage", "/seen <player>");
                return;
            }
            openChooser(live, viewer, "Seen — who?");
            return;
        }
        Optional<OfflinePlayer> found = Players.find(live.server(), args[0]);
        if (found.isEmpty()) {
            live.messages().send(sender, "essentials.no-such-player", "player", args[0]);
            return;
        }
        report(live, sender, found.get());
    }

    /**
     * A full, browsable list of everybody the server has ever seen — online first, offline included
     * — rather than a name that has to be spelled correctly. Shared with {@link PlayersCommand}: the
     * list is the same list either command asks for, so it is built once, here.
     */
    static void openChooser(EssentialsServices live, Player viewer, String heading) {
        new PlayerChooser(viewer, live.brand(), null, heading,
                Players.directory(live.server(), live.core().vanish(), viewer.getUniqueId()),
                List.of(), entry -> report(live, viewer,
                        live.server().getOfflinePlayer(entry.id())))
                .open();
    }

    /** What {@code /seen <player>} says about somebody, wherever the pick came from. */
    static void report(EssentialsServices live, CommandSender sender, OfflinePlayer them) {
        String name = Players.nameOf(them);
        SeenService.Seen seen = SeenService.of(them);

        boolean hiddenFromSender = seen.online()
                && (!(sender instanceof Player viewer)
                        || !live.core().vanish().canSee(viewer.getUniqueId(), them.getUniqueId()));
        if (hiddenFromSender) {
            // Vanished, and this sender may not know it. "Online" is exactly the fact vanish
            // exists to hide, so this reports the same thing it would if they really had logged
            // off a moment ago — same as anybody else who is not currently here.
            seen = new SeenService.Seen(seen.everJoined(), seen.playtime(), seen.firstJoined(), false,
                    Optional.of(Instant.ofEpochMilli(them.getLastLogin())));
        }

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
            EssentialsServices live = services.get();
            String typed = args.length == 1 ? args[0] : "";
            CommandSender sender = source.getSender();
            return sender instanceof Player viewer
                    ? Players.suggestions(live.server(), typed, live.core().vanish(), viewer.getUniqueId())
                    : Players.suggestions(live.server(), typed, live.core().vanish());
        }
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.SEEN;
    }
}
