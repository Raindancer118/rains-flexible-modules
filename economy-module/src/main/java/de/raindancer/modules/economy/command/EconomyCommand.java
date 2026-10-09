package de.raindancer.modules.economy.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.modules.economy.EconomyServices;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** What every command here shares: finding its services, the player, an amount and a target. */
abstract class EconomyCommand implements IEconomyCommand {

    private final Supplier<EconomyServices> services;

    EconomyCommand(Supplier<EconomyServices> services) {
        this.services = services;
    }

    EconomyServices live() {
        return services.get();
    }

    @Override
    public final void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        run(live(), source.getSender(), args);
    }

    abstract void run(EconomyServices live, CommandSender sender, String[] args);

    /** The sender as a player, or a sentence saying only players can do this. */
    static Optional<Player> player(EconomyServices live, CommandSender sender) {
        if (sender instanceof Player player) {
            return Optional.of(player);
        }
        live.messages().send(sender, "economy.only-a-player");
        return Optional.empty();
    }

    static boolean allowed(EconomyServices live, CommandSender sender, String node) {
        if (sender.hasPermission(node)) {
            return true;
        }
        live.messages().send(sender, "economy.not-allowed");
        if (sender instanceof Player player) {
            live.effects().play(player.getUniqueId(), Cues.NO);
        }
        return false;
    }

    static Optional<Money> amount(EconomyServices live, CommandSender sender, String typed) {
        return read(live, sender, typed, Money::isPositive);
    }

    /** Like {@link #amount} but zero is allowed — a balance can be set to nothing. */
    static Optional<Money> balance(EconomyServices live, CommandSender sender, String typed) {
        return read(live, sender, typed, money -> !money.isNegative());
    }

    private static Optional<Money> read(EconomyServices live, CommandSender sender, String typed,
                                        java.util.function.Predicate<Money> allowed) {
        Optional<Money> read = live.currency().parse(typed).filter(allowed);
        if (read.isEmpty()) {
            live.messages().send(sender, "economy.not-an-amount-typed", "typed", typed);
        }
        return read;
    }

    /** Anybody the server knows by that name or nickname, online or not. */
    static Optional<OfflinePlayer> target(EconomyServices live, CommandSender sender, String typed) {
        Optional<OfflinePlayer> found = PlayerTargets.find(live.server(), sender, typed);
        if (found.isEmpty()) {
            live.messages().send(sender, "economy.unknown-player", "player", typed);
        }
        return found;
    }

    static Optional<Player> online(EconomyServices live, CommandSender sender, String typed) {
        Optional<Player> found = PlayerTargets.online(live.server(), sender, typed);
        if (found.isEmpty()) {
            live.messages().send(sender, "economy.not-online", "player", typed);
        }
        return found;
    }

    static String rest(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    static List<String> starting(String typed, Collection<String> options) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    List<String> players(CommandSourceStack source, String typed) {
        return PlayerTargets.suggest(live().server(), source.getSender(), typed, who -> true);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        return List.of();
    }
}
