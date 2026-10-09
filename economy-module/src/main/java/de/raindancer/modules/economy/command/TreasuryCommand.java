package de.raindancer.modules.economy.command;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.command.CommandSender;

import java.util.function.Supplier;

/** {@code /treasury}: how much money there is and what the treasury holds, when the owner lets everybody see. */
public final class TreasuryCommand extends EconomyCommand {

    public TreasuryCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        if (!allowed(live, sender, PermissionNodes.TREASURY)) {
            return;
        }
        if (!live.supply().current().treasuryInfo() && !sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "economy.treasury.off");
            return;
        }
        Scheduling.async(live.plugin(), () -> {
            var health = live.supply().health();
            Scheduling.global(live.plugin(), () -> SupplyReport.send(live, sender, health));
        });
    }

    @Override
    public String describe() {
        return "how much money there is";
    }
}
