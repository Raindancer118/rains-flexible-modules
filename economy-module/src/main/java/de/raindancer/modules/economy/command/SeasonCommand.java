package de.raindancer.modules.economy.command;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.function.Supplier;

/** {@code /season}: your points from every season so far. */
public final class SeasonCommand extends EconomyCommand {

    public SeasonCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        if (!allowed(live, sender, PermissionNodes.SEASON)) {
            return;
        }
        if (!live.supply().current().seasons()) {
            live.messages().send(sender, "economy.season.off");
            return;
        }
        player(live, sender).ifPresent(player -> Scheduling.async(live.plugin(), () -> {
            Map<Integer, Long> points = live.seasons().pointsOf(player.getUniqueId());
            int current = live.seasons().current();
            Scheduling.entity(live.plugin(), player, () -> {
                if (points.isEmpty()) {
                    live.messages().send(player, "economy.season.none", "season", String.valueOf(current));
                    return;
                }
                live.messages().send(player, "economy.season.head");
                points.forEach((season, scored) -> live.messages().send(player, "economy.season.line",
                        "season", String.valueOf(season), "points", String.valueOf(scored)));
            });
        }));
    }

    @Override
    public String describe() {
        return "your season points";
    }
}
