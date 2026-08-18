package de.raindancer.modules.speedrun;

import de.raindancer.modules.speedrun.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * {@code /speedrun} — teleports the sender to the lobby world's spawn.
 *
 * <p>The one thing nothing else here does. Every other entry point — the compass, the start block,
 * {@code /lemmemove}, {@code /starthere} — assumes a player is already standing in the lobby world;
 * none of them ever explains how a player gets there in the first place. On a server with no warp,
 * portal or spawn placement already pointed at it, there was no way in at all.
 */
public final class SpeedrunJoinCommand implements ISpeedrunCommand {

    private final Supplier<SpeedrunAdminServices> services;

    public SpeedrunJoinCommand(Supplier<SpeedrunAdminServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        SpeedrunAdminServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "speedrun.join.only-a-player");
            return;
        }
        String worldName = live.lobby().config().worldName();
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            live.messages().send(sender, "speedrun.join.world-missing", "world", worldName);
            return;
        }
        player.teleportAsync(world.getSpawnLocation());
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.JOIN;
    }

    @Override
    public String describe() {
        return "teleport to the speedrun lobby world";
    }
}
