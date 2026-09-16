package de.raindancer.modules.worldutils.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.worldutils.WorldUtilsServices;
import de.raindancer.modules.worldutils.model.Dimension;
import de.raindancer.modules.worldutils.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** {@code /dim <overworld|nether|end> [player or selector]}. */
public final class DimensionCommand implements IWorldUtilsCommand {

    private final Supplier<WorldUtilsServices> services;

    public DimensionCommand(Supplier<WorldUtilsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        WorldUtilsServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            live.messages().send(sender, "worldutils.usage.dim");
            return;
        }
        Dimension target = Dimension.parse(args[0]).orElse(null);
        if (target == null) {
            live.messages().send(sender, "worldutils.unknown-dimension", "value", args[0]);
            return;
        }
        Targets.of(live, sender, args, 1, PermissionNodes.DIMENSION_OTHERS).ifPresent(players -> {
            for (Player player : players) {
                live.travel().toDimension(sender, player, target);
            }
        });
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return Dimension.words().stream().filter(word -> word.startsWith(typed)).toList();
        }
        if (args.length == 2 && source.getSender().hasPermission(PermissionNodes.DIMENSION_OTHERS)) {
            return PlayerTargets.suggest(services.get().server(), args[1]);
        }
        return List.of();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.DIMENSION;
    }

    @Override
    public String describe() {
        return "going to the same place in another dimension of this world";
    }
}
