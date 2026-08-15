package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** {@code /msg <player> <text>} — a private message. */
public final class MsgCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public MsgCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "sends somebody a private message";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player from)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        if (args.length < 2) {
            live.messages().send(from, "essentials.usage", "usage", "/msg <player> <message>");
            return;
        }
        Player to = live.server().getPlayerExact(args[0]);
        if (to == null) {
            live.messages().send(from, "essentials.no-such-player", "player", args[0]);
            return;
        }
        String text = String.join(" ", List.of(args).subList(1, args.length));
        live.messaging().send(from, to, text);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return Players.suggestions(services.get().server(), args.length == 1 ? args[0] : "");
        }
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.MSG;
    }
}
