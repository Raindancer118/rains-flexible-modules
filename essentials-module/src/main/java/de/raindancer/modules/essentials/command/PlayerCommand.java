package de.raindancer.modules.essentials.command;

import de.raindancer.core.ui.profile.ProfileMenu;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /player <name>} — straight to that one player's profile, for whoever already knows who
 * they want rather than browsing {@code /players}' list for them. Bare {@code /player} is the same
 * list {@code /players} opens; the two commands are two doors onto the one feature; picking a name
 * out of chat or typing it here both land on the same {@link ProfileMenu}.
 */
public final class PlayerCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public PlayerCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "opens one player's profile directly, by name";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            if (!(sender instanceof Player viewer)) {
                live.messages().send(sender, "essentials.usage", "usage", "/player <name>");
                return;
            }
            SeenCommand.openChooser(live, viewer, "Players");
            return;
        }
        Optional<OfflinePlayer> found = Players.find(live.server(), args[0]);
        if (found.isEmpty()) {
            live.messages().send(sender, "essentials.no-such-player", "player", args[0]);
            return;
        }
        OfflinePlayer them = found.get();
        if (sender instanceof Player viewer) {
            new ProfileMenu(viewer, live.brand(), null, them.getUniqueId(), Players.nameOf(them)).open();
            return;
        }
        // No inventory to open one in — the same report /seen <name> gives, which is the closest
        // thing to a profile the console can be shown at all.
        SeenCommand.report(live, sender, them);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            EssentialsServices live = services.get();
            String typed = args.length == 1 ? args[0] : "";
            CommandSender sender = source.getSender();
            return sender instanceof Player viewer
                    ? Players.suggestions(live.server(), typed, live.core().vanish(), viewer.getUniqueId())
                    : Players.suggestions(live.server(), typed, live.core().vanish());
        }
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.PLAYERS;
    }
}
