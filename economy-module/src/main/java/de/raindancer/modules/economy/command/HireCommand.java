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
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /hire <player> <wage> <every> [job]}, {@code /hire} for the jobs screen, {@code /hire fire <player>},
 * {@code /hire quit <player>}.
 */
public final class HireCommand extends EconomyCommand {

    public HireCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        player(live, sender).ifPresent(player -> {
            if (!allowed(live, sender, PermissionNodes.HIRE)) {
                return;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                live.screens().jobs(player);
                return;
            }
            if ((args[0].equalsIgnoreCase("fire") || args[0].equalsIgnoreCase("quit")) && args.length >= 2) {
                boolean firing = args[0].equalsIgnoreCase("fire");
                Optional<Contract> job = live.hire().contractsOf(player.getUniqueId()).stream()
                        .filter(contract -> firing
                                ? contract.employer().equals(player.getUniqueId())
                                && contract.employeeName().equalsIgnoreCase(args[1])
                                : contract.employee().equals(player.getUniqueId())
                                && contract.employerName().equalsIgnoreCase(args[1]))
                        .findFirst();
                if (job.isEmpty()) {
                    live.messages().send(player, "economy.hire.not-yours");
                    return;
                }
                live.hire().end(player, job.get());
                return;
            }
            if (args.length < 3) {
                live.messages().send(player, "economy.usage.hire");
                return;
            }
            var every = Times.parse(args[2]);
            if (every.isEmpty()) {
                live.messages().send(player, "economy.usage.hire");
                return;
            }
            online(live, sender, args[0]).ifPresent(who -> amount(live, sender, args[1]).ifPresent(wage ->
                    live.hire().offer(player, who, wage, every.get(), rest(args, 3))));
        });
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length <= 1) {
            List<String> options = new java.util.ArrayList<>(List.of("list", "fire", "quit"));
            options.addAll(players(source, args.length == 0 ? "" : args[0]));
            return starting(args.length == 0 ? "" : args[0], options);
        }
        if (args.length == 3) {
            return starting(args[2], List.of("30m", "1h", "6h", "1d", "1w"));
        }
        return args.length == 2 && (args[0].equalsIgnoreCase("fire") || args[0].equalsIgnoreCase("quit"))
                ? players(source, args[1]) : List.of();
    }

    @Override
    public String describe() {
        return "hiring somebody for a wage paid at an interval, and ending jobs";
    }
}
