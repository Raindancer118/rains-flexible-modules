package de.raindancer.modules.voicebridge.command;

import de.raindancer.modules.voicebridge.VoiceBridgeServices;
import de.raindancer.modules.voicebridge.model.BridgeStatus;
import de.raindancer.modules.voicebridge.service.GroupService;
import de.raindancer.modules.voicebridge.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /voicebridge} opens the page. {@code join}/{@code leave} because typing beats a menu
 * mid-conversation; {@code status} because the console has no menu; {@code reconnect} because after
 * pasting a token there is nothing to click; {@code link} because the code has to be read and typed
 * elsewhere; {@code group …} and {@code invite} take names and passwords a menu cannot ask for.
 */
public final class VoiceBridgeCommand implements IVoiceBridgeCommand {

    private static final List<String> SUBCOMMANDS = List.of("join", "leave", "status", "link", "unlink",
            "groups", "group", "invite", "reconnect");
    private static final List<String> GROUP_SUBCOMMANDS = List.of("join", "leave", "create");

    private final Supplier<VoiceBridgeServices> services;

    public VoiceBridgeCommand(Supplier<VoiceBridgeServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        VoiceBridgeServices live = services.get();
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "status" -> status(live, sender);
            case "reconnect" -> reconnect(live, sender);
            case "" -> {
                if (!(sender instanceof Player player)) {
                    status(live, sender);
                } else if (allowed(live, player)) {
                    live.screens().root(player);
                }
            }
            case "join", "leave", "link", "unlink", "groups", "group", "invite" -> {
                if (!(sender instanceof Player player)) {
                    live.messages().send(sender, "voicebridge.only-a-player");
                } else if (allowed(live, player)) {
                    asPlayer(live, player, sub, args);
                }
            }
            default -> live.messages().send(sender, "voicebridge.usage");
        }
    }

    private static boolean allowed(VoiceBridgeServices live, Player player) {
        if (player.hasPermission(PermissionNodes.USE)) {
            return true;
        }
        live.messages().send(player, "voicebridge.not-allowed");
        return false;
    }

    private static void asPlayer(VoiceBridgeServices live, Player player, String sub, String[] args) {
        switch (sub) {
            case "join" -> answer(live, player, PermissionNodes.svc(player, GroupService.SVC_GROUPS_PERMISSION)
                    ? live.bridge().join(player.getUniqueId(), live.groups().isBridged(player.getUniqueId()))
                    : "voicebridge.groups.no-permission", "voicebridge.join.done");
            case "leave" -> answer(live, player, live.bridge().leave(player.getUniqueId()), "voicebridge.leave.done");
            case "link" -> live.messages().send(player, "voicebridge.link.code", "code",
                    live.links().codeFor(player.getUniqueId()));
            case "unlink" -> {
                live.lobby().unlinked(player.getUniqueId());
                live.messages().send(player, live.links().unlink(player.getUniqueId())
                        ? "voicebridge.link.unlinked" : "voicebridge.link.not-linked");
            }
            case "groups" -> live.screens().groups(player);
            case "group" -> group(live, player, args);
            case "invite" -> {
                Player target = args.length < 2 ? null : live.server().getPlayerExact(args[1]);
                if (target == null) {
                    live.messages().send(player, "voicebridge.groups.no-such-player",
                            "player", args.length < 2 ? "" : args[1]);
                    return;
                }
                answer(live, player, live.groups().invite(player.getUniqueId(), target.getUniqueId()),
                        "voicebridge.groups.invite-sent", "player", target.getName());
            }
            default -> live.messages().send(player, "voicebridge.usage");
        }
    }

    private static void group(VoiceBridgeServices live, Player player, String[] args) {
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "join" -> {
                if (args.length < 3) {
                    live.messages().send(player, "voicebridge.groups.usage");
                    return;
                }
                answer(live, player, live.groups().join(player.getUniqueId(), args[2], args.length > 3 ? args[3] : null),
                        "voicebridge.groups.joined");
            }
            case "leave" -> answer(live, player, live.groups().leave(player.getUniqueId()), "voicebridge.groups.left");
            case "create" -> {
                if (args.length < 3) {
                    live.messages().send(player, "voicebridge.groups.usage");
                    return;
                }
                answer(live, player, live.groups().create(player.getUniqueId(), args[2],
                                args.length > 3 ? args[3] : null, args.length > 4 ? args[4] : "normal"),
                        "voicebridge.groups.created");
            }
            default -> live.messages().send(player, "voicebridge.groups.usage");
        }
    }

    private static void answer(VoiceBridgeServices live, Player player, String refusal, String done, Object... values) {
        live.messages().send(player, refusal.isEmpty() ? done : refusal, values);
    }

    private static void status(VoiceBridgeServices live, CommandSender sender) {
        BridgeStatus status = live.bridge().status();
        switch (status.phase()) {
            case CONNECTED -> {
                if (status.channelName().isEmpty()) {
                    live.messages().send(sender, "voicebridge.status.lobby-only");
                } else {
                    live.messages().send(sender, "voicebridge.status.connected",
                            "channel", status.channelName(),
                            "discord", status.discordMembers().isEmpty() ? "-" : String.join(", ", status.discordMembers()),
                            "group", live.gateway().groupName(),
                            "count", String.valueOf(live.gateway().members().size()));
                }
                if (live.config().proximityEnabled()) {
                    live.messages().send(sender, "voicebridge.status.proximity",
                            "lines", String.valueOf(live.lobby().linesInUse()));
                }
            }
            case CONNECTING -> live.messages().send(sender, "voicebridge.status.connecting");
            case OFF, FAILED -> live.messages().send(sender, status.detail());
        }
    }

    private static void reconnect(VoiceBridgeServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "voicebridge.reconnect.not-allowed");
            return;
        }
        live.bridge().reconnect();
        live.messages().send(sender, "voicebridge.reconnect.started");
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length <= 1) {
            String started = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> matching = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.equals("reconnect") && !source.getSender().hasPermission(PermissionNodes.ADMIN)) {
                    continue;
                }
                if (sub.startsWith(started)) {
                    matching.add(sub);
                }
            }
            return matching;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && first.equals("group")) {
            return GROUP_SUBCOMMANDS.stream().filter(sub -> sub.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && first.equals("group") && args[1].equalsIgnoreCase("join")) {
            return services.get().groups().groups().stream().map(group -> group.name())
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && first.equals("invite")) {
            return services.get().server().getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "the Discord voice bridge page, linking, voice groups, and reconnecting the bot";
    }
}
