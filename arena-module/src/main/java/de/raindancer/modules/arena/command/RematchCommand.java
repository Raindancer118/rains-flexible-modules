package de.raindancer.modules.arena.command;

import de.raindancer.modules.arena.ArenaServices;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** {@code /rematch}: the same fight again, once everybody in it asks — what the [Rematch] button does. */
public final class RematchCommand implements IArenaCommand {

    private final Supplier<ArenaServices> services;

    public RematchCommand(Supplier<ArenaServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        ArenaServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "arena.only-a-player");
            return;
        }
        live.rematches().ask(player);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        return List.of();
    }

    @Override
    public String describe() {
        return "asking for the last fight again";
    }
}
