package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /repair [player]} mends the item in the hand; {@code /repairall [player]} mends everything
 * somebody carries. One class for both, because the only difference is how much and which node.
 */
public final class RepairCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;
    private final boolean everything;

    public RepairCommand(Supplier<EssentialsServices> services, boolean everything) {
        this.services = services;
        this.everything = everything;
    }

    @Override
    public String describe() {
        return everything ? "repairs everything somebody carries" : "repairs the item in somebody's hand";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length > 1) {
            live.messages().send(sender, "essentials.usage", "usage",
                    everything ? "/repairall [player]" : "/repair [player]");
            return;
        }
        List<Player> targets;
        if (args.length == 1) {
            targets = Players.online(live.messages(), live.server(), sender, args[0], false,
                    "essentials.repair.nobody-there");
            if (targets.isEmpty()) {
                return;
            }
        } else if (sender instanceof Player self) {
            targets = List.of(self);
        } else {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        for (Player target : targets) {
            if (everything) {
                live.repairing().repairAll(sender, target);
            } else {
                live.repairing().repairHand(sender, target);
            }
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length > 1 || !sender.hasPermission(PermissionNodes.REPAIR_OTHERS)) {
            return List.of();
        }
        EssentialsServices live = services.get();
        return Players.suggest(live.server(), sender, args.length == 0 ? "" : args[0], live.core().vanish());
    }

    @Override
    public String permission() {
        return everything ? PermissionNodes.REPAIR_ALL : PermissionNodes.REPAIR;
    }
}
