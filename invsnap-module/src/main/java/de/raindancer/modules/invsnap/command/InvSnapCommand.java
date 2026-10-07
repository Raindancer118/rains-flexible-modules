package de.raindancer.modules.invsnap.command;

import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.invsnap.InvSnapServices;
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
        PlayerLookup lookup = PlayerTargets.lookup(live.server(), sender, args[0]);
        if (lookup.kind() == PlayerLookup.Kind.SELECTOR_REFUSED) {
            live.messages().send(sender, "invsnap.selector-refused", "selector", args[0]);
            return;
        }
        if (lookup.matches().size() > 1) {
            live.messages().send(sender, "invsnap.too-many", "selector", args[0],
                    "count", String.valueOf(lookup.matches().size()));
            return;
        }
        Optional<OfflinePlayer> target = lookup.single();
        if (target.isEmpty()) {
            if (lookup.kind() == PlayerLookup.Kind.SELECTOR) {
                live.messages().send(sender, "invsnap.selector-nobody", "selector", args[0]);
            } else {
                live.messages().send(sender, "invsnap.unknown-player", "player", args[0]);
            }
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
        return PlayerTargets.suggest(services.get().server(), source.getSender(), args[0], who -> true);
    }

    @Override
    public String describe() {
        return "browsing and restoring a player's inventory snapshots";
    }
}
