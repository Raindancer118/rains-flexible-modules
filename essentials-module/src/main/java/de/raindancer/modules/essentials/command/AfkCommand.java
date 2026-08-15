package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/** {@code /afk} — marks yourself away, or back, on purpose rather than waiting for the timeout. */
public final class AfkCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public AfkCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "marks you away from the keyboard, or back, right now";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player who)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        live.afk().toggle(who);
    }

    @Override
    public String permission() {
        return PermissionNodes.AFK;
    }
}
