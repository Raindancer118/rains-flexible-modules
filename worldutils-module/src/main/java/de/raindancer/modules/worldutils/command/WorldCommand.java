package de.raindancer.modules.worldutils.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.worldutils.WorldUtilsServices;
import de.raindancer.modules.worldutils.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** {@code /w [world] [player or selector]}. */
public final class WorldCommand implements IWorldUtilsCommand {

    private final Supplier<WorldUtilsServices> services;

    public WorldCommand(Supplier<WorldUtilsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        WorldUtilsServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            WorldList.send(live, sender);
            return;
        }
        World world = live.server().getWorld(args[0]);
        if (world == null) {
            live.messages().send(sender, "worldutils.unknown-world", "world", args[0]);
            return;
        }
        Targets.of(live, sender, args, 1, PermissionNodes.WORLD_OTHERS).ifPresent(players -> {
            for (Player player : players) {
                live.travel().toWorld(sender, player, world);
            }
        });
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        WorldUtilsServices live = services.get();
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return live.server().getWorlds().stream().map(World::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        if (args.length == 2 && source.getSender().hasPermission(PermissionNodes.WORLD_OTHERS)) {
            return PlayerTargets.suggest(live.server(), args[1]);
        }
        return List.of();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.WORLD;
    }

    @Override
    public String describe() {
        return "going to another world, or sending somebody there";
    }
}
