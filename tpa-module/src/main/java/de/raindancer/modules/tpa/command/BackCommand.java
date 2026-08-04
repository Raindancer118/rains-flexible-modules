package de.raindancer.modules.tpa.command;

import de.raindancer.modules.tpa.TpaServices;
import de.raindancer.modules.tpa.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /back} — going back to where you were, or where you died.
 *
 * <h2>What changed in the port</h2>
 * It now undoes <em>any</em> teleport, not only this plugin's. The waypoints are Core's, recorded by the
 * one class that performs every arrival — so going home and then typing {@code /back} takes somebody
 * back home, which it did not before: {@code /back} lived here, so only teleport requests were
 * remembered, and after {@code /home} it took people to wherever their last request had been from.
 */
public final class BackCommand implements ITpaCommand {

    private final Supplier<TpaServices> services;

    public BackCommand(Supplier<TpaServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        TpaServices live = services.get();
        CommandSender sender = source.getSender();

        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "tpa.only-a-player");
            return;
        }
        live.back().go(player);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        // It takes no arguments at all, so suggesting anything would suggest it does.
        return List.of();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.BACK;
    }

    @Override
    public String describe() {
        return "go back to where you were, or where you died";
    }
}
