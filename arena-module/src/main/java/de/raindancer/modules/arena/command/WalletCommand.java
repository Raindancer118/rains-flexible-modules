package de.raindancer.modules.arena.command;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.api.ArenaProtocol;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@code /balance} and {@code /pay <player> <amount>} here — the survival server's money, through RainsCore's linked
 * economy: one balance on both servers. Somebody on survival can be paid by name. Off the server thread, since every
 * call asks survival.
 */
public final class WalletCommand implements IArenaCommand {

    private final Supplier<ArenaServices> services;
    private final boolean pay;

    public WalletCommand(Supplier<ArenaServices> services, boolean pay) {
        this.services = services;
        this.pay = pay;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        ArenaServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "arena.only-a-player");
            return;
        }
        Optional<Economy> found = Economies.current();
        if (found.isEmpty()) {
            live.messages().send(player, "arena.wallet.unavailable");
            return;
        }
        Economy money = found.get();
        if (!pay) {
            Scheduling.async(live.plugin(), () -> live.messages().send(player, "arena.wallet.balance",
                    "amount", money.format(money.balance(player.getUniqueId()))));
            return;
        }
        if (args.length < 2) {
            live.messages().send(player, "arena.wallet.pay-usage");
            return;
        }
        Optional<Money> amount = money.currency().parse(args[1]);
        if (amount.isEmpty() || !amount.get().isPositive()) {
            live.messages().send(player, "arena.wallet.bad-amount", "typed", args[1]);
            return;
        }
        boolean confirmed = args.length > 2 && args[2].equalsIgnoreCase("confirm");
        Optional<de.raindancer.core.platform.link.ServerLink> link = live.core().link();
        if (link.isEmpty()) {
            live.messages().send(player, "arena.wallet.unavailable");
            return;
        }
        whom(live, player, args[0]).thenCompose(target -> {
            if (target.isEmpty()) {
                live.messages().send(player, "arena.wallet.nobody", "player", args[0]);
                return CompletableFuture.completedFuture(null);
            }
            Who to = target.get();
            // Run on survival by its economy, under every rule a /pay there has — never a raw transfer from here.
            return link.get().request(de.raindancer.core.social.economy.LinkedEconomy.PAY, Map.of(
                            "payer", player.getUniqueId().toString(), "payerName", player.getName(),
                            "target", to.id().toString(), "amount", String.valueOf(amount.get().minor()),
                            "confirmed", String.valueOf(confirmed)), java.time.Duration.ofSeconds(6))
                    .thenAccept(answer -> {
                        if (!answer.containsKey("lines")) {
                            live.messages().send(player, "arena.wallet.failed");
                            return;
                        }
                        net.kyori.adventure.text.serializer.gson.GsonComponentSerializer json =
                                net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();
                        for (String line : answer.get("lines").split("\n")) {
                            if (!line.isBlank()) {
                                player.sendMessage(json.deserialize(line));
                            }
                        }
                        if ("true".equals(answer.get("confirm"))) {
                            live.messages().send(player, "arena.wallet.confirm", "player", to.name(),
                                    "amount", money.format(amount.get()), "typed", java.math.BigDecimal.valueOf(
                                            amount.get().minor(), money.currency().decimals()).toPlainString());
                            return;
                        }
                        Player here = live.server().getPlayer(to.id());
                        if (here != null && "true".equals(answer.get("paid"))) {
                            live.messages().send(here, "arena.wallet.got", "player", player.getName(),
                                    "amount", money.format(amount.get()));
                        }
                    });
        }).exceptionally(failure -> {
            live.messages().send(player, "arena.wallet.failed");
            return null;
        });
    }

    private record Who(UUID id, String name) {
    }

    /** Somebody here, or somebody on survival — asked over the link by name. */
    private static CompletableFuture<Optional<Who>> whom(ArenaServices live, Player asking, String name) {
        Player here = live.server().getPlayerExact(name);
        if (here != null && live.core().vanish().canSee(asking.getUniqueId(), here.getUniqueId())) {
            return CompletableFuture.completedFuture(Optional.of(new Who(here.getUniqueId(), here.getName())));
        }
        if (!live.link().isUp()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return live.link().ask(ArenaProtocol.WHERE, Map.of("name", name)).thenApply(answer ->
                "true".equals(answer.get("online"))
                        ? Optional.of(new Who(UUID.fromString(answer.get("player")), answer.getOrDefault("name", name)))
                        : Optional.<Who>empty());
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (!pay || args.length > 1) {
            return List.of();
        }
        ArenaServices live = services.get();
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return Stream.concat(live.server().getOnlinePlayers().stream().map(Player::getName),
                        live.core().playersElsewhere().stream()).distinct()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).sorted().toList();
    }

    @Override
    public String describe() {
        return pay ? "paying somebody, here or on survival" : "your balance, the same as on survival";
    }
}
