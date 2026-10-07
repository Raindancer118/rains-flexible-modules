package de.raindancer.modules.playerutils.command;

import de.raindancer.modules.playerutils.PlayerUtilsServices;
import de.raindancer.modules.playerutils.model.Action;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** {@code /spectate <player>} to watch, {@code /spectate} or {@code /spectate stop} to come back. */
public final class SpectateCommand implements IPlayerUtilsCommand {

    private final Supplier<PlayerUtilsServices> services;
    private final ActionCommand watch;

    public SpectateCommand(Supplier<PlayerUtilsServices> services) {
        this.services = services;
        this.watch = new ActionCommand(services, Action.SPECTATE);
    }

    @Override
    public String describe() {
        return Action.SPECTATE.describe();
    }

    @Override
    public String permission() {
        return Action.SPECTATE.node();
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        PlayerUtilsServices live = services.get();
        if (!(source.getSender() instanceof Player viewer)) {
            live.messages().send(source.getSender(), "playerutils.only-a-player");
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("stop") || args[0].equalsIgnoreCase("back")) {
            if (!live.spectate().stop(viewer)) {
                live.messages().send(viewer, "playerutils.spectate.not-watching");
            }
            return;
        }
        watch.run(viewer, args);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        List<String> options = new ArrayList<>(watch.suggest(source, args));
        if (args.length <= 1 && "stop".startsWith(args.length == 0 ? "" : args[0].toLowerCase())) {
            options.addFirst("stop");
        }
        return options;
    }
}
