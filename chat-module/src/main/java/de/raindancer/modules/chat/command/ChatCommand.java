package de.raindancer.modules.chat.command;

import de.raindancer.core.ui.chat.ChatChannels;

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
    private static final String USAGE = "/chat team|all|private|public|clear|freeze|slowmode <seconds|off>|say <player> <message>";

    /** Opens the channel picker — a seam, because a real menu needs a running server. */
    private java.util.function.BiConsumer<ChatServices, Player> channelMenu =
            (live, viewer) -> new de.raindancer.modules.chat.screen.ChatChannelMenu(live, viewer).open();

    void channelMenu(java.util.function.BiConsumer<ChatServices, Player> opener) {
        this.channelMenu = opener;
    }

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
            if (sender instanceof Player player) {
                channelMenu.accept(live, player);
            } else {
                live.messages().send(sender, "chat.usage", "usage", USAGE);
            }
            return;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("say")) {
            say(live, sender, args);
            return;
        }
        if (subcommand.equals(ChatChannels.ALL) || ChatChannels.byId(subcommand).isPresent()
                || subcommand.equals("team")) {
            if (!(sender instanceof Player player)) {
                live.messages().send(sender, "chat.only-a-player");
                return;
            }
            switchChannel(live, player, subcommand);
            return;
        }
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

    /**
     * {@code /chat team}, {@code /chat all}, or any other channel a module registered. Refused — and
     * nothing changed — when the player has no part in that channel right now.
     */
    public static void switchChannel(ChatServices live, Player player, String id) {
        if (id.equalsIgnoreCase(ChatChannels.ALL)) {
            live.privateChat().goPublic(player.getUniqueId());
            ChatChannels.select(player.getUniqueId(), ChatChannels.ALL);
            live.messages().send(player, "chat.channel.now", "channel", "All");
            return;
        }
        java.util.Optional<de.raindancer.core.ui.chat.ChatChannel> channel = ChatChannels.byId(id);
        if (channel.isEmpty() || channel.get().audienceFor(player.getUniqueId()).isEmpty()) {
            live.messages().send(player, "chat.channel.unavailable", "channel", id);
            return;
        }
        ChatChannels.select(player.getUniqueId(), channel.get().id());
        live.messages().send(player, "chat.channel.now", "channel", channel.get().label());
    }

    /**
     * {@code /chat say <player> <message>}: what the Pelican panel's chat page sends. The console's alone — a
     * player speaking in somebody else's name would be impersonation, whatever their rank.
     */
    private void say(ChatServices live, CommandSender sender, String[] args) {
        if (!isConsole(sender)) {
            live.messages().send(sender, "chat.say.console-only");
            return;
        }
        if (args.length < 3) {
            live.messages().send(sender, "chat.usage", "usage", "/chat say <player> <message>");
            return;
        }
        java.util.Optional<org.bukkit.OfflinePlayer> who =
                de.raindancer.core.platform.command.PlayerTargets.byRealName(live.server(), args[1])
                        .filter(found -> found.getName() != null && (found.isOnline() || found.hasPlayedBefore()));
        if (who.isEmpty()) {
            live.messages().send(sender, "chat.say.unknown", "player", args[1]);
            return;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        switch (live.sayAs().say(who.get(), text)) {
            case MUTED -> live.messages().send(sender, "chat.say.muted", "player", who.get().getName());
            case EMPTY -> live.messages().send(sender, "chat.say.empty");
            case SAID -> {
            }
        }
    }

    private static boolean isConsole(CommandSender sender) {
        return sender instanceof org.bukkit.command.ConsoleCommandSender
                || sender instanceof org.bukkit.command.RemoteConsoleCommandSender;
    }

    private static java.util.UUID idOf(CommandSender sender) {
        return sender instanceof org.bukkit.entity.Player player ? player.getUniqueId() : null;
    }

    private void clear(ChatServices live, CommandSender sender) {
        live.network().clear(idOf(sender), sender.getName());
    }

    private void freeze(ChatServices live, CommandSender sender) {
        live.network().freeze(!live.freeze().isFrozen(), idOf(sender), sender.getName());
    }

    private void slowmode(ChatServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "chat.usage", "usage", "/chat slowmode <seconds|off>");
            return;
        }
        if (args[1].equalsIgnoreCase("off")) {
            live.network().slowmode(-1, idOf(sender), sender.getName());
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
        live.network().slowmode(seconds, idOf(sender), sender.getName());
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        boolean staff = sender.hasPermission(PermissionNodes.ADMIN);
        boolean mayTalkPrivately = sender instanceof Player && sender.hasPermission(PermissionNodes.PRIVATE);
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> offered = new ArrayList<>();
            if (sender instanceof Player) {
                offered.add(ChatChannels.ALL);
                offered.add("team");
                ChatChannels.all().forEach(channel -> {
                    if (!offered.contains(channel.id())) {
                        offered.add(channel.id());
                    }
                });
            }
            if (mayTalkPrivately) {
                offered.addAll(EVERYBODY);
            }
            if (staff) {
                offered.addAll(STAFF);
            }
            if (isConsole(sender)) {
                offered.add("say");
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
