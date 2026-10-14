package de.raindancer.modules.economy.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomyClient;
import de.raindancer.modules.economy.service.BankClient;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code /balance}, {@code /pay} and {@code /baltop} on a server that is not the bank: each is a question to the bank.
 * The asking blocks, so it runs off the server thread; answers are only ever shown from the callbacks.
 */
final class ClientWallet {

    private record Who(UUID id, String name) {
    }

    private ClientWallet() {
    }

    static void balance(EconomyClient client, CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!client.usable()) {
            client.refuse(sender);
            return;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                client.messages().send(sender, "economy.only-a-player");
            } else if (allowed(client, sender, PermissionNodes.BALANCE)) {
                show(client, sender, new Who(player.getUniqueId(), player.getName()), true);
            }
            return;
        }
        if (allowed(client, sender, PermissionNodes.BALANCE_OTHERS)) {
            who(client, sender, args[0]).ifPresentOrElse(who -> show(client, sender, who, false),
                    () -> client.messages().send(sender, "economy.client.nobody", "player", args[0]));
        }
    }

    private static void show(EconomyClient client, CommandSender sender, Who who, boolean self) {
        Scheduling.async(client.plugin(), () -> {
            Optional<Economy> economy = Economies.current();
            Optional<BankClient.Balance> balance = economy.flatMap(money -> client.bank().balance(money, who.id()));
            if (balance.isEmpty()) {
                unavailable(client, sender);
            } else if (self) {
                client.messages().send(sender, "economy.client.balance-yours",
                        "amount", economy.get().currency().render(balance.get().amount()),
                        "server", client.bank().bankName());
            } else if (!balance.get().account()) {
                client.messages().send(sender, "economy.client.no-account", "player", who.name());
            } else {
                client.messages().send(sender, "economy.client.balance-theirs", "player", who.name(),
                        "amount", economy.get().currency().render(balance.get().amount()));
            }
        });
    }

    static void pay(EconomyClient client, CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!client.usable()) {
            client.refuse(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            client.messages().send(sender, "economy.only-a-player");
            return;
        }
        if (!allowed(client, sender, PermissionNodes.PAY)) {
            return;
        }
        if (args.length < 2) {
            client.messages().send(player, "economy.client.pay-usage");
            return;
        }
        Optional<Who> target = who(client, sender, args[0]);
        if (target.isEmpty()) {
            client.messages().send(player, "economy.client.nobody", "player", args[0]);
            return;
        }
        boolean confirmed = args.length > 2 && args[2].equalsIgnoreCase("confirm");
        Scheduling.async(client.plugin(), () -> {
            Optional<Economy> economy = Economies.current();
            if (economy.isEmpty()) {
                unavailable(client, sender);
                return;
            }
            Currency currency = economy.get().currency();
            Optional<Money> amount = currency.parse(args[1]).filter(Money::isPositive);
            if (amount.isEmpty()) {
                client.messages().send(player, "economy.not-an-amount-typed", "typed", args[1]);
                return;
            }
            client.bank().pay(player.getUniqueId(), player.getName(), target.get().id(), amount.get(), confirmed)
                    .thenAccept(answer -> answered(client, player, target.get(), currency, amount.get(), answer))
                    .exceptionally(failure -> {
                        client.messages().send(player, "economy.client.pay-failed");
                        return null;
                    });
        });
    }

    private static void answered(EconomyClient client, Player player, Who target, Currency currency, Money amount,
                                 Optional<BankClient.Payment> answer) {
        if (answer.isEmpty()) {
            client.messages().send(player, "economy.client.pay-failed");
            return;
        }
        for (String line : answer.get().lines()) {
            player.sendMessage(de.raindancer.core.ui.messages.FromElsewhere.line(line));
        }
        if (answer.get().confirm()) {
            client.messages().send(player, "economy.client.pay-confirm", "player", target.name(),
                    "amount", currency.render(amount),
                    "typed", BigDecimal.valueOf(amount.minor(), currency.decimals()).toPlainString());
        }
    }

    static void baltop(EconomyClient client, CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!client.usable()) {
            client.refuse(sender);
            return;
        }
        if (!allowed(client, sender, PermissionNodes.BALTOP)) {
            return;
        }
        UUID asker = sender instanceof Player player ? player.getUniqueId() : new UUID(0, 0);
        Scheduling.async(client.plugin(), () -> {
            Optional<Economy> economy = Economies.current();
            if (economy.isEmpty()) {
                unavailable(client, sender);
                return;
            }
            Currency currency = economy.get().currency();
            client.bank().top(asker).thenAccept(answer -> {
                if (answer.isEmpty()) {
                    unavailable(client, sender);
                } else if (answer.get().off()) {
                    client.messages().send(sender, "economy.baltop.off");
                } else {
                    client.messages().send(sender, "economy.client.top-header", "server", client.bank().bankName());
                    int place = 0;
                    for (var entry : answer.get().entries()) {
                        client.messages().send(sender, "economy.client.top-line", "place", String.valueOf(++place),
                                "player", entry.name(), "amount", currency.render(entry.balance()));
                    }
                    if (answer.get().place() > 0) {
                        client.messages().send(sender, "economy.client.top-you", "place",
                                String.valueOf(answer.get().place()));
                    }
                }
            });
        });
    }

    static Collection<String> players(EconomyClient client, CommandSourceStack source, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        Stream<String> elsewhere = client.core().networkPlayers()
                .map(players -> players.everyone(false).stream().map(Presence.Here::name)).orElseGet(Stream::empty);
        return Stream.concat(PlayerTargets.suggest(client.server(), source.getSender(), typed, who -> true).stream(),
                        elsewhere)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().sorted().toList();
    }

    /** Somebody here, then somebody online on another server, then anybody this server has seen — by name or nickname. */
    private static Optional<Who> who(EconomyClient client, CommandSender sender, String typed) {
        Optional<Who> here = PlayerTargets.online(client.server(), sender, typed)
                .map(found -> new Who(found.getUniqueId(), PlayerTargets.shownName(found)));
        if (here.isPresent()) {
            return here;
        }
        Optional<Who> elsewhere = client.core().networkPlayers().flatMap(players -> players.named(typed, false))
                .map(found -> new Who(found.player(), found.name()));
        if (elsewhere.isPresent()) {
            return elsewhere;
        }
        return PlayerTargets.find(client.server(), sender, typed)
                .map(found -> new Who(found.getUniqueId(), PlayerTargets.shownName(found)));
    }

    private static boolean allowed(EconomyClient client, CommandSender sender, String node) {
        if (sender.hasPermission(node)) {
            return true;
        }
        client.messages().send(sender, "economy.not-allowed");
        return false;
    }

    private static void unavailable(EconomyClient client, CommandSender sender) {
        client.messages().send(sender, "economy.client.unavailable", "server", client.bank().bankName());
    }
}
