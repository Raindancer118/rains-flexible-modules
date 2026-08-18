package de.raindancer.modules.speedrun;

import de.raindancer.modules.speedrun.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /lemmemove [player]} — exempts a player from the speedrun movement freeze (the READY-state
 * one and the countdown's own) without touching their place in the race. See {@link SpeedrunLobby#release}
 * for exactly what this does and does not do; it keeps racing, it only lifts the freeze.
 *
 * <p>Bare, it releases whoever typed it. With a name, it releases somebody else instead — gated on
 * {@link PermissionNodes#LEMMEMOVE_OTHERS} rather than {@link PermissionNodes#LEMMEMOVE_SELF}, since
 * that is the form that can actually hand somebody a head start over the racers who did not ask for one.
 */
public final class SpeedrunLemmemoveCommand implements ISpeedrunCommand {

    private final Supplier<SpeedrunAdminServices> services;

    /**
     * @param services asked for when the command runs, never captured — see {@link ISpeedrunCommand} on
     *                 why a command built at bootstrap cannot hold anything the module built
     */
    public SpeedrunLemmemoveCommand(Supplier<SpeedrunAdminServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        SpeedrunAdminServices live = services.get();
        CommandSender sender = source.getSender();

        Player target;
        if (args.length > 0) {
            if (!sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)) {
                live.messages().send(sender, "speedrun.lemmemove.no-permission-for-others");
                return;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                live.messages().send(sender, "speedrun.lemmemove.player-not-found", "player", args[0]);
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            live.messages().send(sender, "speedrun.lemmemove.console-needs-a-player");
            return;
        }

        live.lobby().release(target.getUniqueId());
        live.messages().send(sender, "speedrun.lemmemove.done", "player", target.getName());
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length > 1 || !source.getSender().hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)) {
            return List.of();
        }
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.LEMMEMOVE_SELF;
    }

    @Override
    public String describe() {
        return "escape the speedrun movement freeze";
    }
}
