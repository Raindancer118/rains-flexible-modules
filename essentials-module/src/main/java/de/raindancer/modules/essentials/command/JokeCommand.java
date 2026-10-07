package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/** {@code /joke} — a random, deliberately terrible joke, in chat as you. */
public final class JokeCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public JokeCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "tells a terrible joke in chat";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        if (!(source.getSender() instanceof Player by)) {
            live.messages().send(source.getSender(), "essentials.only-a-player");
            return;
        }
        live.fun().joke(by);
    }

    @Override
    public String permission() {
        return PermissionNodes.JOKE;
    }
}
