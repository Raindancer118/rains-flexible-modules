package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.service.NetworkWho;
import de.raindancer.modules.essentials.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /list} — everybody online. On a network that is everybody on every server, grouped by server with this one
 * first; a vanished player is left out unless the sender may see them.
 */
public final class ListCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public ListCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "everybody online, on every server of the network";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        NetworkWho who = live.who();
        List<NetworkWho.Group> groups = who.everybody(sender);
        int total = groups.stream().mapToInt(group -> group.players().size()).sum();
        if (!who.isNetwork()) {
            live.messages().send(sender, "essentials.list.alone", "count", String.valueOf(total),
                    "players", names(groups.getFirst()));
            return;
        }
        live.messages().send(sender, "essentials.list.header", "count", String.valueOf(total),
                "servers", String.valueOf(groups.stream().filter(group -> !group.players().isEmpty()).count()));
        for (NetworkWho.Group group : groups) {
            live.messages().send(sender, group.self() ? "essentials.list.this-server" : "essentials.list.server",
                    "server", group.server(), "count", String.valueOf(group.players().size()),
                    "players", names(group));
        }
    }

    private static String names(NetworkWho.Group group) {
        if (group.players().isEmpty()) {
            return "-";
        }
        return group.players().stream().map(one -> one.vanished() ? one.name() + " (vanished)" : one.name())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    @Override
    public String permission() {
        return PermissionNodes.LIST;
    }
}
