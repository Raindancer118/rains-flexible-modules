package de.raindancer.modules.farmlimit.command;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.farmlimit.FarmLimitServices;
import de.raindancer.modules.farmlimit.model.Hotspot;
import de.raindancer.modules.farmlimit.util.PermissionNodes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.function.Supplier;

/** {@code /farms [least]} — the most crowded loaded chunks, so staff can find the farm behind a lag. */
public final class FarmsCommand implements BasicCommand {

    private static final int SHOWN = 10;
    private static final int DEFAULT_LEAST = 40;

    private final Supplier<FarmLimitServices> services;

    public FarmsCommand(Supplier<FarmLimitServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        FarmLimitServices live = services.get();
        CommandSender sender = source.getSender();
        if (Scheduling.isFolia()) {
            live.messages().send(sender, "farmlimit.folia");
            return;
        }
        int least = DEFAULT_LEAST;
        if (args.length >= 1) {
            try {
                least = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException notANumber) {
                live.messages().send(sender, "farmlimit.usage");
                return;
            }
        }
        List<Hotspot> top = live.counter().everywhere(live.server().getWorlds()).top(SHOWN, least);
        if (top.isEmpty()) {
            live.messages().send(sender, "farmlimit.none", "least", least);
            return;
        }
        live.messages().send(sender, "farmlimit.header", "count", top.size(), "least", least);
        for (Hotspot spot : top) {
            live.messages().sendPlain(sender, "farmlimit.line", "total", spot.total(), "kind", spot.mostCommon(),
                    "kind-count", spot.mostCommonCount(), "world", spot.world(), "x", spot.blockX(), "z", spot.blockZ());
        }
    }

    @Override
    public String permission() {
        return PermissionNodes.INSPECT;
    }
}
