package de.raindancer.modules.oneblock.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.oneblock.OneBlockServices;
import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;
import de.raindancer.modules.oneblock.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * {@code /island} from any server of the network: no argument goes to your island; {@code invite} and {@code guest}
 * ask somebody wherever they are, {@code accept} and {@code deny} answer. The island server decides; this only asks
 * and says what it answered.
 */
public final class IslandCommand implements IOneBlockCommand {

    private static final List<String> SUBCOMMANDS = List.of("invite", "guest", "accept", "deny");

    private final Supplier<OneBlockServices> services;

    public IslandCommand(Supplier<OneBlockServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        OneBlockServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "oneblock.only-a-player");
            return;
        }
        if (!player.hasPermission(PermissionNodes.USE)) {
            live.messages().send(player, "oneblock.not-allowed");
            return;
        }
        Requester me = new Requester(player.getUniqueId(), player.getName());
        String other = args.length > 1 ? args[1] : null;
        CompletableFuture<Reply> asked = switch (args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT)) {
            case "" -> live.gateway().home(me);
            case "invite" -> live.gateway().invite(me, other, InviteKind.TEAM);
            case "guest" -> live.gateway().invite(me, other, InviteKind.GUEST);
            case "accept" -> live.gateway().answer(me, other, true);
            case "deny" -> live.gateway().answer(me, other, false);
            default -> CompletableFuture.completedFuture(Reply.refused("oneblock.usage"));
        };
        asked.thenAccept(reply -> Scheduling.entity(live.plugin(), player, () -> said(live, player, reply)));
    }

    private static void said(OneBlockServices live, Player player, Reply reply) {
        if (!reply.key().isEmpty()) {
            live.messages().send(player, reply.key(), reply.values().toArray());
        }
        if (reply.transfer()) {
            ProxyTransfer.send(live.plugin(), player, live.config().proxyServer());
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (!(source.getSender() instanceof Player player) || !player.hasPermission(PermissionNodes.USE)) {
            return List.of();
        }
        if (args.length <= 1) {
            String started = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(started)).toList();
        }
        OneBlockServices live = services.get();
        String typed = args[1];
        List<String> names = new ArrayList<>(PlayerTargets.suggest(live.server(), source.getSender(), typed,
                online -> !online.equals(player)));
        String lowered = typed.toLowerCase(Locale.ROOT);
        live.core().playersElsewhere().stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lowered))
                .filter(name -> !names.contains(name)).sorted().forEach(names::add);
        return names;
    }

    @Override
    public String describe() {
        return "going to your island, and invites from anywhere on the network";
    }
}
