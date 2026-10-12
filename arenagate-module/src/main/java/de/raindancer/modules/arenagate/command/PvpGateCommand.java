package de.raindancer.modules.arenagate.command;

import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.api.SpecArgs;
import de.raindancer.modules.arena.api.SpecCodec;
import de.raindancer.modules.arenagate.GateServices;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@code /pvp} on the survival server: {@code invite <player> [duel|sumo|waves] [kit|own] [stake] [bo3]} — somebody here
 * or somebody in the arena — {@code accept [id]}, {@code deny [id]}, and {@code queue [mode] [kit] [stake]}, which goes
 * to the arena and straight into its queue.
 */
public final class PvpGateCommand implements IGateCommand {

    private static final List<String> SUBCOMMANDS = List.of("invite", "accept", "deny", "cancel", "queue");

    private final Supplier<GateServices> services;

    public PvpGateCommand(Supplier<GateServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        GateServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "arenagate.only-a-player");
            return;
        }
        String what = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (live.network() == null && !what.equals("queue")) {
            live.messages().send(player, "arenagate.unreachable");
            return;
        }
        switch (what) {
            case "invite" -> {
                if (args.length < 2) {
                    live.messages().send(player, "arenagate.pvp.usage");
                    return;
                }
                MatchSpec spec = SpecArgs.parse(args, 2, Mode.DUEL);
                if (spec.mode() != Mode.DUEL && spec.mode() != Mode.SUMO && spec.mode() != Mode.WAVES
                        && spec.mode() != Mode.NAVAL && spec.mode() != Mode.MOUNTED && spec.mode() != Mode.HORSEBACK) {
                    live.messages().send(player, "arenagate.pvp.only-duels");
                    return;
                }
                live.network().invite(player, args[1], spec);
            }
            case "accept" -> live.network().accept(player, args.length > 1 ? args[1] : null);
            case "deny" -> live.network().deny(player, args.length > 1 ? args[1] : null);
            case "cancel" -> live.network().cancel(player, args.length > 1 ? args[1] : null);
            case "queue" -> {
                MatchSpec queued = SpecArgs.parse(args, 1, Mode.DUEL);
                if (live.consents() != null) {
                    live.consents().agree(player.getUniqueId(), queued.stake(), queued.isOwnGear());
                }
                Map<String, String> intent = new HashMap<>(SpecCodec.encode(queued));
                intent.put("kind", "QUEUE");
                live.gate().go(player, intent);
            }
            default -> live.messages().send(player, "arenagate.pvp.usage");
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length <= 1) {
            return SUBCOMMANDS.stream().filter(option -> option.startsWith(last)).toList();
        }
        if (args[0].equalsIgnoreCase("invite") && args.length == 2) {
            return Stream.concat(services.get().plugin().getServer().getOnlinePlayers().stream().map(Player::getName),
                            services.get().elsewhere().get().stream()).distinct()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(last)).sorted().toList();
        }
        return Stream.of("duel", "sumo", "waves", "2v2", "3v3", "ffa", "own", "bo3", "gladiator", "knight", "netherite",
                "archer", "axe", "mace", "potion", "uhc").filter(option -> option.startsWith(last)).toList();
    }

    @Override
    public String describe() {
        return "asking somebody to an arena fight, answering, and the queue";
    }
}
