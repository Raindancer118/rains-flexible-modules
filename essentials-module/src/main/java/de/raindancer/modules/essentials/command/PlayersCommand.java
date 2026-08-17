package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * {@code /players} — everybody the server has ever seen, online first, offline included. A player
 * gets {@link de.raindancer.core.ui.choose.PlayerChooser}, the same door bare {@code /seen} opens;
 * the console gets the same list read out as text, since there is no inventory to open one in.
 */
public final class PlayersCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public PlayersCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "everybody the server has ever seen, online first, offline included";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (sender instanceof Player viewer) {
            SeenCommand.openChooser(live, viewer, "Players");
            return;
        }
        listAsText(live, sender);
    }

    /** What the console (or a command block) gets instead of a menu: the same list, read out. */
    private void listAsText(EssentialsServices live, CommandSender sender) {
        var everybody = Players.directory(live.server(), live.core().vanish(), null).everybody();
        if (everybody.isEmpty()) {
            live.messages().send(sender, "essentials.players.none");
            return;
        }
        live.messages().send(sender, "essentials.players.header", "count",
                String.valueOf(everybody.size()));
        for (var entry : everybody) {
            OfflinePlayer them = live.server().getOfflinePlayer(entry.id());
            SeenCommand.report(live, sender, them);
        }
    }

    @Override
    public String permission() {
        return PermissionNodes.PLAYERS;
    }
}
