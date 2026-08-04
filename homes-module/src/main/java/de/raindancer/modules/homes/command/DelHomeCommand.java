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
 * {@code /delhome [name]} — forgetting one.
 *
 * <h2>Why this one does not confirm and the menu does</h2>
 * Because typing the name is the confirmation. Somebody who has typed {@code /delhome themine} has
 * spelled out which of their homes they mean; a second prompt after that is the sort of thing people
 * learn to click through. The menu is different — there the home is one click from three other buttons,
 * so it goes through {@code ConfirmScreen}.
 */
public final class DelHomeCommand implements IHomeCommand {

    private final Supplier<HomeServices> services;

    public DelHomeCommand(Supplier<HomeServices> services) {
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
        live.keeping().delete(player, args.length == 0 ? null : args[0]);
    }

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
        return "forget one of your homes";
    }
}
