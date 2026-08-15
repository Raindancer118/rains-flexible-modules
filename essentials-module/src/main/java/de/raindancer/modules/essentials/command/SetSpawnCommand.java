package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/** {@code /setspawn} — moves the server's spawn point to where the caller stands. */
public final class SetSpawnCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public SetSpawnCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "moves the server's spawn point to where you stand";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        live.spawn().set(player.getLocation(), player.getUniqueId());
        live.messages().send(player, "essentials.spawn.set");
    }

    @Override
    public String permission() {
        return PermissionNodes.SET_SPAWN;
    }
}
