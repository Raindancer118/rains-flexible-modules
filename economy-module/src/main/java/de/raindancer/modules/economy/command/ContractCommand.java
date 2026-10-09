package de.raindancer.modules.economy.command;

import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Contract;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /contract pay|charge <player> <amount> <every> <what>} — a regular payment for a service, proposed by
 * either side and started once the other accepts; {@code /contract} for the list, {@code /contract end <player>}.
 */
public final class ContractCommand extends EconomyCommand {

    public ContractCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        player(live, sender).ifPresent(player -> {
            if (!allowed(live, sender, PermissionNodes.HIRE)) {
                return;
            }
            String word = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
            if (word.equals("list")) {
                live.screens().jobs(player);
                return;
            }
            if (word.equals("end") && args.length >= 2) {
                Optional<Contract> deal = live.hire().contractsOf(player.getUniqueId()).stream()
                        .filter(Contract::isService)
                        .filter(contract -> (contract.employer().equals(player.getUniqueId())
                                ? contract.employeeName() : contract.employerName()).equalsIgnoreCase(args[1]))
                        .findFirst();
                if (deal.isEmpty()) {
                    live.messages().send(player, "economy.contract.not-yours");
                    return;
                }
                live.hire().end(player, deal.get());
                return;
            }
            if (!(word.equals("pay") || word.equals("charge")) || args.length < 5) {
                live.messages().send(player, "economy.usage.contract");
                return;
            }
            var every = Times.parse(args[3]);
            if (every.isEmpty()) {
                live.messages().send(player, "economy.usage.contract");
                return;
            }
            online(live, sender, args[1]).ifPresent(who -> amount(live, sender, args[2]).ifPresent(amount ->
                    live.hire().propose(player, who, word.equals("pay"), amount, every.get(), rest(args, 4))));
        });
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length <= 1) {
            return starting(args.length == 0 ? "" : args[0], List.of("pay", "charge", "list", "end"));
        }
        if (args.length == 2) {
            return players(source, args[1]);
        }
        if (args.length == 4 && !args[0].equalsIgnoreCase("end")) {
            return starting(args[3], List.of("1h", "6h", "1d", "1w"));
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "regular payments for a service, like rent — proposed by either side";
    }
}
