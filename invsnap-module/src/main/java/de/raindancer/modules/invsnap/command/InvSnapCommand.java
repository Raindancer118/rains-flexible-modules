package de.raindancer.modules.invsnap.command;

import de.raindancer.modules.invsnap.InvSnapServices;
import de.raindancer.modules.invsnap.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /invsnap <player>} opens that player's snapshot history directly; bare {@code /invsnap}
 * opens the full picker instead, for the times an admin does not already know who they are looking
 * for. Admin-only; there is nothing here for a player to run on their own inventory.
 */
public final class InvSnapCommand implements IInvSnapCommand {

    private final Supplier<InvSnapServices> services;

    public InvSnapCommand(Supplier<InvSnapServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        InvSnapServices live = services.get();
        CommandSender sender = source.getSender();

        if (!(sender instanceof Player admin)) {
            live.messages().send(sender, "invsnap.only-a-player");
            return;
        }
        if (args.length < 1) {
            live.screens().root(admin);
            return;
        }
        Optional<OfflinePlayer> target = Players.find(live.server(), args[0]);
        if (target.isEmpty()) {
            live.messages().send(sender, "invsnap.unknown-player", "player", args[0]);
            return;
        }
        OfflinePlayer found = target.get();
        String targetName = found.getName() == null ? args[0] : found.getName();
        live.screens().history(admin, found.getUniqueId(), targetName);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length != 1) {
            return List.of();
        }
        return Players.suggestions(services.get().server(), args[0]);
    }

    @Override
    public String describe() {
        return "browsing and restoring a player's inventory snapshots";
    }
}
