package de.raindancer.modules.economy.command;

import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** {@code /loan} for the loan screen, {@code /loan take <amount>}, {@code /loan repay [amount|all]}. */
public final class LoanCommand extends EconomyCommand {

    public LoanCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        player(live, sender).ifPresent(player -> {
            if (!allowed(live, sender, PermissionNodes.LOAN)) {
                return;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
                live.screens().loan(player);
                return;
            }
            switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
                case "take", "borrow" -> {
                    if (args.length < 2) {
                        live.messages().send(player, "economy.usage.loan");
                        return;
                    }
                    amount(live, sender, args[1]).ifPresent(amount -> live.loans().borrow(player, amount));
                }
                case "repay", "pay" -> {
                    if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
                        live.loans().repay(player, Optional.empty());
                        return;
                    }
                    amount(live, sender, args[1]).ifPresent(amount -> live.loans().repay(player, Optional.of(amount)));
                }
                default -> live.messages().send(player, "economy.usage.loan");
            }
        });
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length <= 1) {
            return starting(args.length == 0 ? "" : args[0], List.of("take", "repay", "info"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("repay")) {
            return starting(args[1], List.of("all"));
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "borrowing from the bank and paying it back";
    }
}
