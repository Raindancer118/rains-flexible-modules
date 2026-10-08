package de.raindancer.modules.economy.command;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Raffle;
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
 * {@code /raffle} for the running raffles, {@code /raffle start <ticket price> [length] [most tickets]
 * [per player]} for the item in hand, {@code /raffle money <prize> <ticket price> [length] …}, {@code /raffle buy <number> [tickets]}, {@code /raffle cancel <number>}, {@code /raffle info}.
 */
public final class RaffleCommand extends EconomyCommand {

    public RaffleCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        if (sub.equals("info") || !(sender instanceof org.bukkit.entity.Player) && sub.isEmpty()) {
            info(live, sender);
            return;
        }
        player(live, sender).ifPresent(player -> {
            if (!allowed(live, sender, PermissionNodes.AUCTION)) {
                return;
            }
            switch (sub) {
                case "" -> live.screens().raffles(player);
                case "start" -> start(live, player, args);
                case "money" -> money(live, player, args);
                case "buy" -> {
                    Optional<Integer> number = whole(args, 1);
                    if (number.isEmpty()) {
                        live.messages().send(player, "economy.usage.raffle");
                        return;
                    }
                    live.raffles().buy(player, number.get(), whole(args, 2).orElse(1));
                }
                case "cancel" -> whole(args, 1).ifPresentOrElse(number -> live.raffles().cancel(player, number, false),
                        () -> live.messages().send(player, "economy.usage.raffle"));
                default -> live.messages().send(player, "economy.usage.raffle");
            }
        });
    }

    /** {@code start <ticket price> [length] [most tickets] [per player]} — the length is anything like 30m. */
    private static void start(EconomyServices live, org.bukkit.entity.Player player, String[] args) {
        if (args.length < 2) {
            live.messages().send(player, "economy.usage.raffle");
            return;
        }
        Optional<Money> price = amount(live, player, args[1]);
        if (price.isEmpty()) {
            return;
        }
        int minutes = 0;
        int next = 2;
        if (args.length > 2 && args[2].matches(".*[a-zA-Z]$")) {
            Optional<Duration> length = Times.parse(args[2]);
            if (length.isEmpty()) {
                live.messages().send(player, "economy.usage.raffle");
                return;
            }
            minutes = (int) Math.max(1, Math.min(Integer.MAX_VALUE, length.get().toMinutes()));
            next = 3;
        }
        live.raffles().start(player, price.get(), minutes, whole(args, next).orElse(0), whole(args, next + 1).orElse(0));
    }

    /** {@code money <prize> <ticket price> [length] [most tickets] [per player]}. */
    private static void money(EconomyServices live, org.bukkit.entity.Player player, String[] args) {
        if (args.length < 3) {
            live.messages().send(player, "economy.usage.raffle");
            return;
        }
        Optional<Money> prize = amount(live, player, args[1]);
        Optional<Money> price = prize.isEmpty() ? Optional.empty() : amount(live, player, args[2]);
        if (price.isEmpty()) {
            return;
        }
        int minutes = 0;
        int next = 3;
        if (args.length > 3 && args[3].matches(".*[a-zA-Z]$")) {
            Optional<Duration> length = Times.parse(args[3]);
            if (length.isEmpty()) {
                live.messages().send(player, "economy.usage.raffle");
                return;
            }
            minutes = (int) Math.max(1, Math.min(Integer.MAX_VALUE, length.get().toMinutes()));
            next = 4;
        }
        live.raffles().startMoney(player, prize.get(), price.get(), minutes, whole(args, next).orElse(0),
                whole(args, next + 1).orElse(0));
    }

    static Optional<Integer> whole(String[] args, int at) {
        if (args.length <= at) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(args[at].replace("#", "")));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    private static void info(EconomyServices live, CommandSender sender) {
        List<Raffle> running = live.raffles().raffles();
        if (running.isEmpty()) {
            live.messages().send(sender, "economy.raffle.list-none");
            return;
        }
        for (Raffle raffle : running) {
            live.messages().send(sender, "economy.raffle.status", "number", String.valueOf(raffle.number()),
                    "item", live.raffles().shown(raffle), "price", live.currency().render(raffle.ticketPrice()),
                    "total", String.valueOf(raffle.sold()), "duration", Times.describe(live.raffles().left(raffle)));
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length <= 1) {
            return starting(args.length == 0 ? "" : args[0], List.of("start", "money", "buy", "cancel", "info"));
        }
        if ((args[0].equalsIgnoreCase("buy") || args[0].equalsIgnoreCase("cancel")) && args.length == 2) {
            EconomyServices live = de.raindancer.modules.economy.EconomyCommands.isRunning() ? live() : null;
            return live == null ? List.of() : starting(args[1], live.raffles().raffles().stream()
                    .map(raffle -> String.valueOf(raffle.number())).toList());
        }
        if (args[0].equalsIgnoreCase("start") && args.length == 3) {
            return starting(args[2], List.of("15m", "30m", "1h", "1d"));
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "raffles: raffling an item off, buying tickets, and the draw";
    }
}
