package de.raindancer.modules.economy.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** {@code /balance [player]} — yours, or somebody else's. */
public final class BalanceCommand extends EconomyCommand {

    public BalanceCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        if (args.length == 0) {
            player(live, sender).ifPresent(player -> {
                if (allowed(live, sender, PermissionNodes.BALANCE)) {
                    int place = live.leaderboard().placeOf(player.getUniqueId());
                    live.messages().send(player, "economy.balance.yours",
                            "amount", live.currency().render(live.economy().balance(player.getUniqueId())),
                            "place", String.valueOf(place));
                }
            });
            return;
        }
        if (!allowed(live, sender, PermissionNodes.BALANCE_OTHERS)) {
            return;
        }
        target(live, sender, args[0]).ifPresent(who -> live.messages().send(sender, "economy.balance.theirs",
                "player", PlayerTargets.shownName(who),
                "amount", live.currency().render(live.economy().balance(who.getUniqueId())),
                "place", String.valueOf(live.leaderboard().placeOf(who.getUniqueId()))));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        return args.length <= 1 ? players(source, args.length == 0 ? "" : args[0]) : List.of();
    }

    @Override
    public String describe() {
        return "your balance, or somebody else's";
    }
}
