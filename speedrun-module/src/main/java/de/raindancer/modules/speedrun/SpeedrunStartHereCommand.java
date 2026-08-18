package de.raindancer.modules.speedrun;

import de.raindancer.modules.speedrun.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * {@code /starthere} — sets where every participant is teleported to once a countdown begins, to the
 * sender's own position. See {@link SpeedrunLobby#setStartPoint} and {@link SpeedrunLobby#beginCountdown}.
 */
public final class SpeedrunStartHereCommand implements ISpeedrunCommand {

    private final Supplier<SpeedrunAdminServices> services;

    public SpeedrunStartHereCommand(Supplier<SpeedrunAdminServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        SpeedrunAdminServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "speedrun.starthere.only-a-player");
            return;
        }
        String worldName = live.lobby().config().worldName();
        if (!player.getWorld().getName().equals(worldName)) {
            live.messages().send(sender, "speedrun.starthere.wrong-world", "world", worldName);
            return;
        }
        live.lobby().setStartPoint(player.getLocation());
        live.messages().send(sender, "speedrun.starthere.done");
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.ADMIN;
    }

    @Override
    public String describe() {
        return "set the speedrun start point to here";
    }
}
