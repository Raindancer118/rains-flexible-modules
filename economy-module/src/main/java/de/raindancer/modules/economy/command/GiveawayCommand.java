package de.raindancer.modules.economy.command;

import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /giveaway} for what is running, {@code /giveaway start [length]} for the item in hand,
 * {@code /giveaway money <amount> [length]}, {@code /giveaway join <number>}, {@code /giveaway cancel <number>}.
 */
public final class GiveawayCommand extends EconomyCommand {

    public GiveawayCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        player(live, sender).ifPresent(player -> {
            if (!allowed(live, sender, PermissionNodes.AUCTION)) {
                return;
            }
            switch (sub) {
                case "" -> live.screens().raffles(player);
                case "start" -> live.raffles().giveItem(player, minutes(args, 1));
                case "money" -> {
                    if (args.length < 2) {
                        live.messages().send(player, "economy.usage.giveaway");
                        return;
                    }
                    amount(live, sender, args[1]).ifPresent(prize ->
                            live.raffles().giveMoney(player, prize, minutes(args, 2)));
                }
                case "join" -> RaffleCommand.whole(args, 1).ifPresentOrElse(
                        number -> live.raffles().buy(player, number, 1),
                        () -> live.messages().send(player, "economy.usage.giveaway"));
                case "cancel" -> RaffleCommand.whole(args, 1).ifPresentOrElse(
                        number -> live.raffles().cancel(player, number, false),
                        () -> live.messages().send(player, "economy.usage.giveaway"));
                default -> live.messages().send(player, "economy.usage.giveaway");
            }
        });
    }

    /** A length like 30m at {@code at}; zero for the default. */
    static int minutes(String[] args, int at) {
        if (args.length <= at) {
            return 0;
        }
        Optional<Duration> length = Times.parse(args[at]);
        return length.map(duration -> (int) Math.max(1, Math.min(Integer.MAX_VALUE, duration.toMinutes()))).orElse(0);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length <= 1) {
            return starting(args.length == 0 ? "" : args[0], List.of("start", "money", "join", "cancel"));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("cancel"))) {
            EconomyServices live = de.raindancer.modules.economy.EconomyCommands.isRunning() ? live() : null;
            return live == null ? List.of() : starting(args[1], live.raffles().raffles().stream()
                    .filter(raffle -> raffle.giveaway()).map(raffle -> String.valueOf(raffle.number())).toList());
        }
        if (args[0].equalsIgnoreCase("start") && args.length == 2) {
            return starting(args[1], List.of("15m", "30m", "1h", "1d"));
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "giveaways: giving something away for free, and joining";
    }
}
