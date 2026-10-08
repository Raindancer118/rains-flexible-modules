package de.raindancer.modules.economy.command;

import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** {@code /withdraw [amount] [cheque]} and {@code /deposit [hand|all]}. */
public final class CashCommand extends EconomyCommand {

    private final boolean withdrawing;

    public CashCommand(Supplier<EconomyServices> services, boolean withdrawing) {
        super(services);
        this.withdrawing = withdrawing;
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        player(live, sender).ifPresent(player -> {
            if (!allowed(live, sender, PermissionNodes.CASH)) {
                return;
            }
            if (withdrawing) {
                if (args.length == 0) {
                    live.screens().withdraw(player);
                    return;
                }
                boolean cheque = args.length > 1 && args[1].equalsIgnoreCase("cheque");
                amount(live, sender, args[0]).ifPresent(amount -> live.cash().withdraw(player, amount, cheque));
            } else if (args.length > 0 && args[0].equalsIgnoreCase("hand")) {
                live.cash().depositHand(player);
            } else {
                live.cash().depositAll(player);
            }
        });
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (withdrawing) {
            return args.length == 2 ? starting(args[1], List.of("cheque")) : List.of();
        }
        return args.length <= 1 ? starting(args.length == 0 ? "" : args[0], List.of("hand", "all")) : List.of();
    }

    @Override
    public String describe() {
        return withdrawing ? "taking money out as coins, notes or a cheque" : "paying cash back in";
    }
}
