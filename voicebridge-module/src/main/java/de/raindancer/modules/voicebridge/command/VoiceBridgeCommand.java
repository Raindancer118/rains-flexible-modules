package de.raindancer.modules.voicebridge.command;

import de.raindancer.modules.voicebridge.VoiceBridgeServices;
import de.raindancer.modules.voicebridge.model.BridgeStatus;
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
 * {@code /voicebridge} opens the page. {@code join} and {@code leave} are there because typing is
 * faster than a menu mid-conversation, {@code status} because the console has no menu, and
 * {@code reconnect} because after pasting a token there is nothing to click yet.
 */
public final class VoiceBridgeCommand implements IVoiceBridgeCommand {

    private static final List<String> SUBCOMMANDS = List.of("join", "leave", "status", "reconnect");

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
            case "join", "leave" -> {
                if (!(sender instanceof Player player)) {
                    live.messages().send(sender, "voicebridge.only-a-player");
                    return;
                }
                if (!player.hasPermission(PermissionNodes.USE)) {
                    live.messages().send(sender, "voicebridge.not-allowed");
                    return;
                }
                String refusal = sub.equals("join")
                        ? live.bridge().join(player.getUniqueId())
                        : live.bridge().leave(player.getUniqueId());
                live.messages().send(sender, refusal.isEmpty()
                        ? (sub.equals("join") ? "voicebridge.join.done" : "voicebridge.leave.done")
                        : refusal);
            }
            case "" -> {
                if (!(sender instanceof Player player)) {
                    status(live, sender);
                    return;
                }
                if (!player.hasPermission(PermissionNodes.USE)) {
                    live.messages().send(sender, "voicebridge.not-allowed");
                    return;
                }
                live.screens().root(player);
            }
            default -> live.messages().send(sender, "voicebridge.usage");
        }
    }

    private static void status(VoiceBridgeServices live, CommandSender sender) {
        BridgeStatus status = live.bridge().status();
        switch (status.phase()) {
            case CONNECTED -> live.messages().send(sender, "voicebridge.status.connected",
                    "channel", status.channelName(),
                    "discord", status.discordMembers().isEmpty() ? "-" : String.join(", ", status.discordMembers()),
                    "group", live.gateway().groupName(),
                    "count", String.valueOf(live.gateway().members().size()));
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
        if (args.length > 1) {
            return List.of();
        }
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

    @Override
    public String describe() {
        return "the Discord voice bridge page, joining and leaving its group, and reconnecting the bot";
    }
}
