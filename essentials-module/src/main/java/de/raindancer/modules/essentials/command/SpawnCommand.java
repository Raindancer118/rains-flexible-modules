package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/** {@code /spawn} — the one place everybody can always get to. */
public final class SpawnCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public SpawnCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "teleports you to the server's spawn point";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        live.spawn().go(player);
    }

    @Override
    public String permission() {
        return PermissionNodes.SPAWN;
    }
}
