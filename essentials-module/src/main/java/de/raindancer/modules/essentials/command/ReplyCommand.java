package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Supplier;

/** {@code /r <text>} — answers whoever this player last spoke to. */
public final class ReplyCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public ReplyCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "answers whoever last messaged you";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player from)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        if (args.length == 0) {
            live.messages().send(from, "essentials.usage", "usage", "/r <message>");
            return;
        }
        UUID target = live.messaging().replyTarget(from.getUniqueId()).orElse(null);
        if (target == null) {
            live.messages().send(from, "essentials.msg.nobody-to-reply-to");
            return;
        }
        Player to = live.server().getPlayer(target);
        if (to == null) {
            live.messages().send(from, "essentials.msg.unreachable", "player", "them");
            return;
        }
        String text = String.join(" ", args);
        live.messaging().send(from, to, text);
    }

    @Override
    public String permission() {
        return PermissionNodes.MSG;
    }
}
