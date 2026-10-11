package de.raindancer.modules.sends.command;

import de.raindancer.modules.sends.SendsServices;
import de.raindancer.modules.sends.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/** {@code /sends} opens the list: what is waiting for you, what you have waiting, and what has come back. */
public final class SendsCommand implements ISendsCommand {

    private final Supplier<SendsServices> services;

    public SendsCommand(Supplier<SendsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        SendsServices live = services.get();
        if (!(source.getSender() instanceof Player viewer)) {
            live.messages().send(source.getSender(), "sends.only-a-player");
            return;
        }
        if (!viewer.hasPermission(PermissionNodes.USE)) {
            live.messages().send(viewer, "sends.not-allowed");
            return;
        }
        live.screens().sends(viewer);
    }

    @Override
    public String describe() {
        return "opening the list of sends";
    }
}
