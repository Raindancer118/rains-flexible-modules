package de.raindancer.modules.economy.command;

import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /coinflip <amount> [heads|tails]}, {@code /coinflip <player> <amount>} (a duel),
 * {@code /dice <amount> <over|under> <number>}, {@code /lottery [buy <n>]}.
 */
public final class GambleCommand extends EconomyCommand {

    public enum Game { COINFLIP, DICE, ROULETTE, LOTTERY, BLACKJACK, BACCARAT, HILO, MINES, CRASH, RACE, SCRATCH }

    private final Game game;

    public GambleCommand(Supplier<EconomyServices> services, Game game) {
        super(services);
        this.game = game;
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        player(live, sender).ifPresent(player -> {
            if (!allowed(live, sender, PermissionNodes.GAMBLE)) {
                return;
            }
            switch (game) {
                case COINFLIP -> {
                    if (args.length == 0) {
                        live.screens().coinflip(player, null, null);
                        return;
                    }
                    if (args.length >= 2 && live.currency().parse(args[0]).isEmpty()) {
                        online(live, sender, args[0]).ifPresent(who -> amount(live, sender, args[1])
                                .ifPresent(stake -> live.gambling().challenge(player, who, stake)));
                        return;
                    }
                    Boolean call = args.length < 2 ? null : args[1].toLowerCase(Locale.ROOT).startsWith("h");
                    amount(live, sender, args[0]).ifPresent(stake -> live.screens().coinflip(player, stake, call));
                }
                case DICE -> {
                    if (args.length == 0) {
                        live.screens().dice(player, null, null, null);
                        return;
                    }
                    if (args.length < 3) {
                        amount(live, sender, args[0]).ifPresent(stake -> live.screens().dice(player, stake, null, null));
                        return;
                    }
                    boolean over = !args[1].toLowerCase(Locale.ROOT).startsWith("u");
                    int target;
                    try {
                        target = Integer.parseInt(args[2]);
                    } catch (NumberFormatException notANumber) {
                        live.messages().send(player, "economy.usage.dice");
                        return;
                    }
                    amount(live, sender, args[0]).ifPresent(stake -> live.screens().dice(player, stake, over, target));
                }
                case ROULETTE -> {
                    if (args.length == 0) {
                        live.screens().roulette(player, null);
                    } else {
                        amount(live, sender, args[0]).ifPresent(stake -> live.screens().roulette(player, stake));
                    }
                }
                case LOTTERY -> {
                    if (args.length >= 1 && args[0].equalsIgnoreCase("buy")) {
                        if (args.length == 1 + live.lottery().pick()) {
                            java.util.List<Integer> numbers = new java.util.ArrayList<>();
                            try {
                                for (int i = 1; i < args.length; i++) {
                                    numbers.add(Integer.parseInt(args[i]));
                                }
                            } catch (NumberFormatException notANumber) {
                                live.messages().send(player, "economy.usage.lottery");
                                return;
                            }
                            live.lottery().buy(player, numbers, 1);
                            return;
                        }
                        int count = 1;
                        if (args.length >= 2) {
                            try {
                                count = Math.max(1, Integer.parseInt(args[1]));
                            } catch (NumberFormatException notANumber) {
                                live.messages().send(player, "economy.usage.lottery");
                                return;
                            }
                        }
                        live.lottery().buy(player, null, count);
                    } else if (args.length >= 1 && args[0].equalsIgnoreCase("info")) {
                        live.lottery().status(player);
                    } else {
                        live.screens().table(player, de.raindancer.modules.economy.model.DealerGame.LOTTERY);
                    }
                }
                case BLACKJACK -> live.screens().table(player, de.raindancer.modules.economy.model.DealerGame.BLACKJACK);
                case BACCARAT -> live.screens().table(player, de.raindancer.modules.economy.model.DealerGame.BACCARAT);
                case HILO -> live.screens().table(player, de.raindancer.modules.economy.model.DealerGame.HILO);
                case MINES -> live.screens().table(player, de.raindancer.modules.economy.model.DealerGame.MINES);
                case CRASH -> live.screens().table(player, de.raindancer.modules.economy.model.DealerGame.CRASH);
                case RACE -> live.screens().table(player, de.raindancer.modules.economy.model.DealerGame.RACE);
                case SCRATCH -> {
                    int count = 1;
                    if (args.length >= 2 && args[0].equalsIgnoreCase("buy")) {
                        try {
                            count = Math.max(1, Integer.parseInt(args[1]));
                        } catch (NumberFormatException notANumber) {
                            count = 1;
                        }
                    }
                    live.scratch().buy(player, count);
                }
            }
        });
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        return switch (game) {
            case COINFLIP -> args.length == 2 ? starting(args[1], List.of("heads", "tails"))
                    : args.length <= 1 ? players(source, args.length == 0 ? "" : args[0]) : List.of();
            case DICE -> args.length == 2 ? starting(args[1], List.of("over", "under")) : List.of();
            case ROULETTE -> List.of();
            case LOTTERY -> args.length <= 1 ? starting(args.length == 0 ? "" : args[0], List.of("buy", "info")) : List.of();
            case SCRATCH -> args.length <= 1 ? starting(args.length == 0 ? "" : args[0], List.of("buy")) : List.of();
            default -> List.of();
        };
    }

    @Override
    public String describe() {
        return switch (game) {
            case COINFLIP -> "a coin flip against the house, or a duel";
            case DICE -> "rolling the dice";
            case ROULETTE -> "the roulette wheel";
            case LOTTERY -> "the lottery";
            case BLACKJACK -> "blackjack against the dealer";
            case BACCARAT -> "baccarat";
            case HILO -> "hi-lo";
            case MINES -> "mines";
            case CRASH -> "the crash round";
            case RACE -> "the horse race";
            case SCRATCH -> "buying scratch cards";
        };
    }
}
