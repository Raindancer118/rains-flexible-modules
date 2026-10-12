package de.raindancer.modules.arena.command;

import de.raindancer.modules.arena.api.SpecArgs;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.model.Kit;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@code /pvp invite <player> [mode] [kit|own] [stake] [bo3]}, {@code accept [id]}, {@code deny [id]},
 * {@code queue [mode] [kit]}, {@code join <player>} (the fight they wait for), {@code leave}, {@code rematch}. An invite works for somebody here and for somebody on survival.
 */
public final class PvpCommand implements IArenaCommand {

    private static final List<String> SUBCOMMANDS = List.of("invite", "accept", "deny", "cancel", "queue", "join", "leave",
            "rematch");

    private final Supplier<ArenaServices> services;

    public PvpCommand(Supplier<ArenaServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        ArenaServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "arena.only-a-player");
            return;
        }
        String what = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (what) {
            case "invite" -> {
                if (args.length < 2) {
                    live.messages().send(player, "arena.pvp.usage");
                    return;
                }
                MatchSpec spec = SpecArgs.parse(args, 2, Mode.DUEL);
                if (spec.mode() != Mode.DUEL && spec.mode() != Mode.SUMO && spec.mode() != Mode.WAVES) {
                    live.messages().send(player, "arena.pvp.only-duels");
                    return;
                }
                live.invites().invite(player, args[1], spec);
            }
            case "accept" -> live.invites().accept(player, args.length > 1 ? args[1] : null);
            case "deny" -> live.invites().deny(player, args.length > 1 ? args[1] : null);
            case "cancel" -> live.invites().cancel(player, args.length > 1 ? args[1] : null);
            case "queue" -> live.queue().join(player, SpecArgs.parse(args, 1, Mode.DUEL));
            case "leave" -> live.messages().send(player, live.queue().leave(player.getUniqueId())
                    ? "arena.queue.left" : "arena.queue.not-in");
            case "join" -> {
                if (args.length < 2) {
                    live.messages().send(player, "arena.pvp.usage");
                    return;
                }
                live.queue().joinLike(player, args[1]);
            }
            case "rematch" -> live.rematches().ask(player);
            default -> live.messages().send(player, "arena.pvp.usage");
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length <= 1) {
            return SUBCOMMANDS.stream().filter(option -> option.startsWith(last)).toList();
        }
        ArenaServices live = services.get();
        if (args[0].equalsIgnoreCase("invite") && args.length == 2) {
            return Stream.concat(live.server().getOnlinePlayers().stream().map(Player::getName),
                            live.core().playersElsewhere().stream()).distinct()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(last)).sorted().toList();
        }
        if (args[0].equalsIgnoreCase("join") && args.length == 2) {
            return live.queue().openQueues().stream().map(open -> live.server().getPlayer(open.waiting().getFirst()))
                    .filter(java.util.Objects::nonNull).map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(last)).toList();
        }
        if (args[0].equalsIgnoreCase("rematch")) {
            return List.of();
        }
        if (args[0].equalsIgnoreCase("cancel") && source.getSender() instanceof Player player) {
            return live.invites().madeBy(player.getUniqueId()).stream().map(invite -> invite.id()).toList();
        }
        if (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny")) {
            if (source.getSender() instanceof Player player) {
                return live.invites().openFor(player.getUniqueId()).stream().map(invite -> invite.id()).toList();
            }
            return List.of();
        }
        return Stream.concat(Stream.of("duel", "sumo", "waves", "own", "bo3", "bo5"),
                live.kits().all().stream().map(Kit::id)).filter(option -> option.startsWith(last)).toList();
    }

    @Override
    public String describe() {
        return "asking somebody to a fight, answering, and the queue";
    }
}
