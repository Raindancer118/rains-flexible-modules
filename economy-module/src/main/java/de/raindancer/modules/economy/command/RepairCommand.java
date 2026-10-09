package de.raindancer.modules.economy.command;

import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.command.CommandSender;

import java.util.function.Supplier;

/** {@code /repair}: what repairing the item in hand costs, with a button to pay. */
public final class RepairCommand extends EconomyCommand {

    public RepairCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        player(live, sender).ifPresent(player -> {
            if (allowed(live, sender, PermissionNodes.REPAIR)) {
                live.repair().offer(player);
            }
        });
    }

    @Override
    public String describe() {
        return "repairing the item in hand";
    }
}
