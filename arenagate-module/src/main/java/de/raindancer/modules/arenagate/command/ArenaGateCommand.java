package de.raindancer.modules.arenagate.command;

import de.raindancer.modules.arenagate.GateServices;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** {@code /arena} on the survival server: off to the arena server. */
public final class ArenaGateCommand implements IGateCommand {

    private final Supplier<GateServices> services;

    public ArenaGateCommand(Supplier<GateServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        GateServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "arenagate.only-a-player");
            return;
        }
        String what = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (what) {
            case "wave", "waves" -> {
                de.raindancer.modules.arena.api.MatchSpec spec = de.raindancer.modules.arena.api.SpecArgs.parse(args, 1,
                        de.raindancer.modules.arena.api.Mode.WAVES);
                if (spec.mode() != de.raindancer.modules.arena.api.Mode.WAVES) {
                    spec = de.raindancer.modules.arena.api.MatchSpec.waves(null, spec.kit());
                }
                java.util.Map<String, String> intent = new java.util.HashMap<>(
                        de.raindancer.modules.arena.api.SpecCodec.encode(spec));
                intent.put("kind", "WAVES");
                live.gate().go(player, intent);
            }
            case "queue" -> {
                java.util.Map<String, String> intent = new java.util.HashMap<>(de.raindancer.modules.arena.api.SpecCodec
                        .encode(de.raindancer.modules.arena.api.SpecArgs.parse(args, 1,
                                de.raindancer.modules.arena.api.Mode.DUEL)));
                intent.put("kind", "QUEUE");
                live.gate().go(player, intent);
            }
            default -> live.gate().go(player);
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
        if (args.length <= 1) {
            return java.util.stream.Stream.of("wave", "queue").filter(option -> option.startsWith(last)).toList();
        }
        if (args[0].equalsIgnoreCase("wave") || args[0].equalsIgnoreCase("waves")) {
            return de.raindancer.modules.arena.api.WaveSpec.PRESETS.keySet().stream().sorted()
                    .filter(option -> option.startsWith(last)).toList();
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "going to the arena server — the hub, a mob run, or the queue";
    }
}
