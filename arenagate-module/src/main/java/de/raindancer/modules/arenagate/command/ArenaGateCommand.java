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
        live.gate().go(player);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        return List.of();
    }

    @Override
    public String describe() {
        return "going to the arena server";
    }
}
