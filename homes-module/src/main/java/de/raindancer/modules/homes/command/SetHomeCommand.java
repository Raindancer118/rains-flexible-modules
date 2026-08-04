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
 * {@code /sethome [name]} — saving where you are standing.
 *
 * <p>With no name it is the one called {@code home}, which is what nearly everybody types. Setting one
 * that already exists moves it rather than refusing, and does not count against the limit — see
 * {@code HomeKeepingService} for why that matters on a server whose owner has just lowered the number.
 */
public final class SetHomeCommand implements IHomeCommand {

    private final Supplier<HomeServices> services;

    public SetHomeCommand(Supplier<HomeServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        HomeServices live = services.get();
        CommandSender sender = source.getSender();

        if (!(sender instanceof Player player)) {
            // A home is set where somebody is standing, and the console is not standing anywhere.
            live.messages().send(sender, "homes.only-a-player");
            return;
        }
        live.keeping().set(player, args.length == 0 ? null : args[0]);
    }

    /**
     * Their existing names, so moving one is as easy as setting a new one.
     *
     * <p>Completing what already exists on a command that creates looks odd written down and is right in
     * practice: "move the home I have" is the more common of the two things this command does.
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
        return "save where you are standing as a home";
    }
}
