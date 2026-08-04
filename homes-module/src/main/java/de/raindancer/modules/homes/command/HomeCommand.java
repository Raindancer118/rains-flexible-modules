package de.raindancer.modules.homes.command;

import de.raindancer.modules.homes.HomeServices;
import de.raindancer.modules.homes.model.Home;
import de.raindancer.modules.homes.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /home} — going to one, or opening the list of them.
 *
 * <h2>Why bare {@code /home} is the list</h2>
 * It could reasonably mean "the one called home", and on a server where somebody called theirs something
 * else that would be a command which always fails for them. The list is the one answer right for
 * everybody, and the home called {@code home} is one click away on it.
 *
 * <p>Player-only, all of it. A home belongs to somebody, and the console is not somebody — the old
 * plugin said exactly that and it is still the clearest way to put it.
 */
public final class HomeCommand implements IHomeCommand {

    private final Supplier<HomeServices> services;

    /**
     * @param services asked for when the command runs, never captured — see {@link IHomeCommand} on why
     *                 a command built at bootstrap cannot hold anything the module built
     */
    public HomeCommand(Supplier<HomeServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        HomeServices live = services.get();
        CommandSender sender = source.getSender();

        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "homes.only-a-player");
            return;
        }
        if (args.length == 0) {
            live.screens().homes(player);
            return;
        }
        Home home = live.homes().find(player.getUniqueId(), args[0]).orElse(null);
        if (home == null) {
            live.keeping().unknown(player, args[0]);
            return;
        }
        live.travelling().go(player, home);
    }

    /**
     * Their own home names, and nothing else.
     *
     * <p>Only in the first argument: completing a second one would suggest {@code /home base base} is a
     * thing.
     */
    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (!(source.getSender() instanceof Player player) || args.length > 1) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return services.get().homes().of(player.getUniqueId()).stream()
                .map(Home::name)
                .filter(name -> name.startsWith(typed))
                .limit(50)
                .toList();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "go to one of your homes, or see the list of them";
    }
}
