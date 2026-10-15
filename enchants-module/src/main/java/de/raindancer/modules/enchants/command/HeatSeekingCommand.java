package de.raindancer.modules.enchants.command;

import de.raindancer.modules.enchants.EnchantsServices;
import de.raindancer.modules.enchants.service.HeatSeekingService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/** {@code /heatseeking} — switches one's Heat-seeking between precise (what the crosshair is on) and wide. */
public final class HeatSeekingCommand implements BasicCommand {

    private final Supplier<EnchantsServices> services;

    public HeatSeekingCommand(Supplier<EnchantsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EnchantsServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "enchants.players-only");
            return;
        }
        live.messages().send(player, HeatSeekingService.toggle(player) ? "enchants.heat-seeking-precise"
                : "enchants.heat-seeking-wide");
    }
}
