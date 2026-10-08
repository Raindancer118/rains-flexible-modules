package de.raindancer.modules.economy.command;

import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** {@code /pay <player> <amount>}. */
public final class PayCommand extends EconomyCommand {

    public PayCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        player(live, sender).ifPresent(player -> {
            if (!allowed(live, sender, PermissionNodes.PAY)) {
                return;
            }
            if (args.length < 2) {
                live.messages().send(player, "economy.usage.pay");
                return;
            }
            target(live, sender, args[0]).ifPresent(who -> amount(live, sender, args[1])
                    .ifPresent(amount -> live.payments().pay(player, who, amount, false)));
        });
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        return args.length <= 1 ? players(source, args.length == 0 ? "" : args[0]) : List.of();
    }

    @Override
    public String describe() {
        return "paying another player";
    }
}
