package de.raindancer.modules.chat.command;

import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.screen.ChatStyleMenu;
import de.raindancer.modules.chat.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /chatstyle} — a colour and decorations for your own chat messages, picked from a swatch
 * grid with a live preview. Gated behind {@link PermissionNodes#STYLE}, which defaults to
 * operators only: unlike everything else this module hands out to every player by default, a
 * server that wants this as a perk for staff — or for a donor rank — grants the node rather than
 * this command assuming everybody should have it.
 */
public final class ChatStyleCommand implements IChatCommand {

    private final Supplier<ChatServices> services;

    public ChatStyleCommand(Supplier<ChatServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "picks a colour and decorations for your own chat messages";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        ChatServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player viewer)) {
            live.messages().send(sender, "chat.only-a-player");
            return;
        }
        new ChatStyleMenu(live, viewer, null).open();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.STYLE;
    }
}
