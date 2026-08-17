package de.raindancer.modules.chat.command;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** {@code /chat clear|freeze|slowmode <seconds|off>} — the staff tools for a chat gone wrong. */
public final class ChatCommand implements IChatCommand {

    private static final List<String> SUBCOMMANDS = List.of("clear", "freeze", "slowmode");

    private final Supplier<ChatServices> services;

    public ChatCommand(Supplier<ChatServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "clears, freezes, or slows down public chat";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        ChatServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            live.messages().send(sender, "chat.usage", "usage", "/chat clear|freeze|slowmode <seconds|off>");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "clear" -> clear(live, sender);
            case "freeze" -> freeze(live, sender);
            case "slowmode" -> slowmode(live, sender, args);
            default -> live.messages().send(sender, "chat.usage",
                    "usage", "/chat clear|freeze|slowmode <seconds|off>");
        }
    }

    private void clear(ChatServices live, CommandSender sender) {
        for (Player online : live.server().getOnlinePlayers()) {
            for (int line = 0; line < 100; line++) {
                live.chat().blank(online);
            }
        }
        live.chat().broadcast(live.messages().raw("chat.admin.cleared"),
                Chat.arg("player", sender.getName()));
    }

    private void freeze(ChatServices live, CommandSender sender) {
        if (live.freeze().isFrozen()) {
            live.freeze().unfreeze();
            live.chat().broadcast(live.messages().raw("chat.admin.unfrozen"),
                    Chat.arg("player", sender.getName()));
        } else {
            live.freeze().freeze();
            live.chat().broadcast(live.messages().raw("chat.admin.frozen"),
                    Chat.arg("player", sender.getName()));
        }
    }

    private void slowmode(ChatServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "chat.usage", "usage", "/chat slowmode <seconds|off>");
            return;
        }
        if (args[1].equalsIgnoreCase("off")) {
            live.quality().clearSlowmodeOverride();
            live.chat().broadcast(live.messages().raw("chat.admin.slowmode-off"),
                    Chat.arg("player", sender.getName()));
            return;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(args[1]);
        } catch (NumberFormatException notANumber) {
            live.messages().send(sender, "chat.usage", "usage", "/chat slowmode <seconds|off>");
            return;
        }
        if (seconds < 0 || seconds > 60) {
            live.messages().send(sender, "chat.admin.slowmode-range");
            return;
        }
        live.quality().overrideSlowmode(seconds);
        live.chat().broadcast(live.messages().raw("chat.admin.slowmode-set"),
                Chat.arg("player", sender.getName()), Chat.arg("seconds", seconds));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(typed)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("slowmode")) {
            return List.of("off", "5", "10", "30");
        }
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.ADMIN;
    }
}
