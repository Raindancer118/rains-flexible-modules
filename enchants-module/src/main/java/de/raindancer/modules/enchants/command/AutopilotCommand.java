package de.raindancer.modules.enchants.command;

import de.raindancer.modules.enchants.EnchantsServices;
import de.raindancer.modules.enchants.screen.AutopilotMenu;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/** {@code /autopilot} — wearing an Autopilot elytra, pick where to fly. */
public final class AutopilotCommand implements BasicCommand {

    private final Supplier<EnchantsServices> services;

    public AutopilotCommand(Supplier<EnchantsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EnchantsServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "enchants.players-only");
            return;
        }
        if (live.autopilot().ready(player)) {
            new AutopilotMenu(player, live.brand(), live.places(), live.autopilot()).open();
        }
    }
}
