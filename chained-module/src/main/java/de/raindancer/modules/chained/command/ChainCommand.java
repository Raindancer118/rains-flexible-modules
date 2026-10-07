package de.raindancer.modules.chained.command;

import de.raindancer.core.world.manage.WorldSeed;
import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.chained.ChainedServices;
import de.raindancer.modules.chained.model.ChainPair;
import de.raindancer.modules.chained.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /chain} — pairing, running and resetting a chained-together speedrun.
 *
 * <h2>Unknown subcommands</h2>
 * There is no predecessor plugin whose old names have to keep answering — this is the first release
 * — but a typo must not read as a working command that silently did nothing. Anything that is not one
 * of the recognised words falls through to {@link #help}, which is the same convention
 * {@code HungerGamesCommands} and the other command classes in this reactor follow.
 */
public final class ChainCommand implements IChainedCommand {

    private final Supplier<ChainedServices> services;

    /**
     * @param services asked for when the command runs, never captured — see {@link IChainedCommand}
     *                 on why a command built at bootstrap cannot hold anything the module built
     */
    public ChainCommand(Supplier<ChainedServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        ChainedServices live = services.get();
        CommandSender sender = source.getSender();

        if (args.length == 0) {
            status(live, sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "pair" -> pair(live, sender, args);
            case "unpair" -> unpair(live, sender, args);
            case "start" -> start(live, sender);
            case "stop" -> stop(live, sender);
            case "reset" -> reset(live, sender, args);
            case "status" -> status(live, sender);
            case "admin" -> admin(live, sender);
            // Anything else did not silently do nothing: it is read as a request for help.
            default -> help(live, sender);
        }
    }

    private void help(ChainedServices live, CommandSender sender) {
        live.messages().lines("chained.help").forEach(sender::sendMessage);
    }

    private void status(ChainedServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "chained.only-a-player");
            return;
        }
        live.screens().status(player);
    }

    private void admin(ChainedServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "chained.not-yours");
            return;
        }
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "chained.only-a-player");
            return;
        }
        live.screens().admin(player);
    }

    private void pair(ChainedServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "chained.not-yours");
            return;
        }
        if (args.length < 3) {
            live.messages().send(sender, "chained.usage.pair");
            return;
        }
        Player first = onlineOne(live, sender, args[1]);
        if (first == null) {
            return;
        }
        Player second = onlineOne(live, sender, args[2]);
        if (second == null) {
            return;
        }
        int maxDistance = live.config().maxDistance();
        if (args.length >= 4) {
            try {
                maxDistance = Integer.parseInt(args[3]);
            } catch (NumberFormatException notANumber) {
                live.messages().send(sender, "chained.usage.pair");
                return;
            }
        }
        try {
            ChainPair made = live.chain().pair(first.getUniqueId(), second.getUniqueId(), maxDistance);
            live.messages().send(sender, "chained.paired",
                    "first", first.getName(), "second", second.getName(),
                    "distance", (int) made.maxDistance());
        } catch (IllegalArgumentException refused) {
            live.messages().send(sender, "chained.pair-refused", "reason", refused.getMessage());
        }
    }

    private void unpair(ChainedServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "chained.not-yours");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "chained.usage.unpair");
            return;
        }
        // Offline is fine here: a pair outlives a disconnect, and so must the way to break it.
        PlayerLookup found = PlayerTargets.lookup(live.server(), sender, args[1]);
        if (found.kind() == PlayerLookup.Kind.SELECTOR_REFUSED) {
            live.messages().send(sender, "chained.selector-refused", "typed", args[1]);
            return;
        }
        for (OfflinePlayer who : found.matches()) {
            if (live.chain().unpair(who.getUniqueId())) {
                live.messages().send(sender, "chained.unpaired", "name", PlayerTargets.shownName(who));
            } else {
                live.messages().send(sender, "chained.not-paired", "name", PlayerTargets.shownName(who));
            }
        }
        if (found.isEmpty()) {
            live.messages().send(sender, "chained.not-paired", "name", args[1]);
        }
    }

    /** The one online player {@code typed} means, or null after telling the sender why not. */
    private Player onlineOne(ChainedServices live, CommandSender sender, String typed) {
        PlayerLookup found = PlayerTargets.lookup(live.server(), sender, typed);
        if (found.kind() == PlayerLookup.Kind.SELECTOR_REFUSED) {
            live.messages().send(sender, "chained.selector-refused", "typed", typed);
        } else if (found.matches().size() > 1) {
            live.messages().send(sender, "chained.too-many", "typed", typed, "count", found.matches().size());
        } else if (found.isEmpty()) {
            live.messages().send(sender, "chained.unknown-player", "name", typed);
        } else if (found.isOfflineOnly()) {
            live.messages().send(sender, "chained.player-offline",
                    "name", PlayerTargets.shownName(found.matches().getFirst()));
        } else {
            return found.online().getFirst();
        }
        return null;
    }

    private void start(ChainedServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "chained.only-a-player");
            return;
        }
        if (live.chain().start(player.getUniqueId()).isEmpty()) {
            live.messages().send(sender, "chained.start-refused");
            return;
        }
        live.messages().send(sender, "chained.started");
    }

    private void stop(ChainedServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "chained.only-a-player");
            return;
        }
        if (!live.chain().stop(player.getUniqueId())) {
            live.messages().send(sender, "chained.stop-refused");
            return;
        }
        live.messages().send(sender, "chained.stopped");
    }

    private void reset(ChainedServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "chained.not-yours");
            return;
        }
        WorldSeed seed = null;
        if (args.length >= 3 && args[1].equalsIgnoreCase("seed")) {
            // Core's reading: a number, "random", "same", or a word hashed the way vanilla hashes it.
            seed = WorldSeed.parse(args[2]).orElse(null);
            if (seed == null) {
                live.messages().send(sender, "chained.usage.reset");
                return;
            }
        } else if (args.length == 2) {
            live.messages().send(sender, "chained.usage.reset");
            return;
        }
        WorldSeed resolvedSeed = seed;
        live.chain().resetWorld(resolvedSeed, done ->
                live.messages().send(sender, done ? "chained.reset-done" : "chained.reset-refused"));
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return startingWith(
                    List.of("pair", "unpair", "start", "stop", "reset", "status", "admin"), typed);
        }
        if (args.length >= 2 && (args[0].equalsIgnoreCase("pair") || args[0].equalsIgnoreCase("unpair"))) {
            de.raindancer.core.moderation.vanish.Vanish vanish = services.get().core().vanish();
            java.util.UUID viewer = source.getSender() instanceof Player asking
                    ? asking.getUniqueId() : null;
            return PlayerTargets.suggest(services.get().server(), source.getSender(), args[args.length - 1],
                    online -> viewer == null || vanish.canSee(viewer, online.getUniqueId()));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            return startingWith(List.of("seed"), args[1].toLowerCase(Locale.ROOT));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            return startingWith(List.of("random"), args[2].toLowerCase(Locale.ROOT));
        }
        return List.of();
    }

    private static Collection<String> startingWith(List<String> options, String typed) {
        return options.stream()
                .filter(word -> word.toLowerCase(Locale.ROOT).startsWith(typed))
                .limit(50)
                .toList();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "pairing, running and resetting a chained-together speedrun";
    }
}
