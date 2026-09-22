package de.raindancer.modules.chat.command;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /chat} — the staff tools for a chat gone wrong ({@code clear}, {@code freeze},
 * {@code slowmode}), and the private chats every player may start ({@code private}, {@code public}).
 *
 * <h2>Why the command itself asks for no permission</h2>
 * It used to require {@code chat.admin} as a whole, which was right while every subcommand was a staff
 * tool. With {@code /chat private} beside them, each half checks its own node — the staff tools
 * {@link PermissionNodes#ADMIN}, the private chats {@link PermissionNodes#PRIVATE} — and a player is
 * only ever offered the subcommands they may actually run.
 */
public final class ChatCommand implements IChatCommand {

    private static final List<String> STAFF = List.of("clear", "freeze", "slowmode");
    private static final List<String> EVERYBODY = List.of("private", "public");
    private static final String USAGE = "/chat private|public|clear|freeze|slowmode <seconds|off>";

    private final Supplier<ChatServices> services;

    public ChatCommand(Supplier<ChatServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "private chats, and clearing, freezing or slowing down public chat";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        ChatServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            live.messages().send(sender, "chat.usage", "usage", USAGE);
            return;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (EVERYBODY.contains(subcommand)) {
            if (!(sender instanceof Player player)) {
                live.messages().send(sender, "chat.only-a-player");
                return;
            }
            if (!player.hasPermission(PermissionNodes.PRIVATE)) {
                live.messages().send(sender, "chat.no-permission");
                return;
            }
            if (subcommand.equals("private")) {
                PrivateChatSubcommand.privately(live, player, args);
            } else {
                PrivateChatSubcommand.publicly(live, player);
            }
            return;
        }
        if (!STAFF.contains(subcommand)) {
            live.messages().send(sender, "chat.usage", "usage", USAGE);
            return;
        }
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "chat.no-permission");
            return;
        }
        switch (subcommand) {
            case "clear" -> clear(live, sender);
            case "freeze" -> freeze(live, sender);
            default -> slowmode(live, sender, args);
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
        CommandSender sender = source.getSender();
        boolean staff = sender.hasPermission(PermissionNodes.ADMIN);
        boolean mayTalkPrivately = sender instanceof Player && sender.hasPermission(PermissionNodes.PRIVATE);
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> offered = new ArrayList<>();
            if (mayTalkPrivately) {
                offered.addAll(EVERYBODY);
            }
            if (staff) {
                offered.addAll(STAFF);
            }
            return offered.stream().filter(name -> name.startsWith(typed)).toList();
        }
        if (args[0].equalsIgnoreCase("private") && mayTalkPrivately) {
            return PrivateChatSubcommand.suggest(services.get(), (Player) sender, args);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("slowmode") && staff) {
            return List.of("off", "5", "10", "30");
        }
        return List.of();
    }

    /** Nothing for the command as a whole — see the class note. */
    @Override
    public String permission() {
        return null;
    }
}
