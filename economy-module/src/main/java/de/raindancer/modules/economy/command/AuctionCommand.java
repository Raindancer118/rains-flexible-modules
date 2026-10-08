package de.raindancer.modules.economy.command;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Auction;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /auction} for the auction house, {@code /auction sell <start> [buy it now] [length]},
 * {@code /auction bid [amount]}, {@code /auction cancel}, {@code /auction claim}, {@code /auction mute},
 * {@code /auction info}.
 */
public final class AuctionCommand extends EconomyCommand {

    public AuctionCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        if (sub.equals("info") || !(sender instanceof Player) && sub.isEmpty()) {
            info(live, sender);
            return;
        }
        player(live, sender).ifPresent(player -> {
            if (!allowed(live, sender, PermissionNodes.AUCTION)) {
                return;
            }
            switch (sub) {
                case "" -> live.screens().auctions(player);
                case "sell" -> sell(live, player, args);
                case "bid" -> {
                    if (args.length < 2) {
                        live.auctions().bid(player, null, null);
                        return;
                    }
                    String meant = args.length > 2 ? args[2] : null;
                    amount(live, sender, args[1]).ifPresent(amount -> live.auctions().bid(player, amount, meant));
                }
                case "cancel" -> {
                    Optional<Auction> latest = live.auctions().auctions().stream()
                            .filter(auction -> auction.seller().equals(player.getUniqueId()))
                            .max(Comparator.comparingLong(Auction::listedAt));
                    if (latest.isEmpty()) {
                        live.messages().send(player, "economy.auction.not-yours");
                        return;
                    }
                    live.auctions().withdraw(player, latest.get().id());
                }
                case "claim" -> {
                    if (live.auctions().claimsOf(player.getUniqueId()).isEmpty()) {
                        live.messages().send(player, "economy.auction.nothing");
                        return;
                    }
                    live.auctions().deliver(player);
                }
                case "mute" -> live.messages().send(player, live.auctions().toggleNews(player)
                        ? "economy.auction.unmuted" : "economy.auction.muted");
                default -> live.messages().send(player, "economy.usage.auction");
            }
        });
    }

    /** {@code sell <start> [buy it now] [length]}: a second amount is the buyout, anything like 5m the length. */
    private static void sell(EconomyServices live, Player player, String[] args) {
        if (args.length < 2) {
            live.messages().send(player, "economy.usage.auction");
            return;
        }
        Optional<Money> start = amount(live, player, args[1]);
        if (start.isEmpty()) {
            return;
        }
        Money buyout = Money.ZERO;
        int seconds = 0;
        for (int i = 2; i < args.length && i < 4; i++) {
            Optional<Duration> length = args[i].matches(".*[a-zA-Z]$") ? Times.parse(args[i]) : Optional.empty();
            if (length.isPresent()) {
                seconds = (int) Math.min(Integer.MAX_VALUE, length.get().toSeconds());
                continue;
            }
            Optional<Money> price = amount(live, player, args[i]);
            if (price.isEmpty()) {
                return;
            }
            buyout = price.get();
        }
        live.auctions().list(player, start.get(), buyout, seconds);
    }

    private static void info(EconomyServices live, CommandSender sender) {
        var currency = live.currency();
        List<Auction> all = live.auctions().auctions();
        Optional<Auction> running = all.stream().filter(Auction::live).findFirst();
        if (running.isEmpty()) {
            live.messages().send(sender, "economy.auction.none");
        } else {
            Auction auction = running.get();
            if (auction.hasBid()) {
                live.messages().send(sender, "economy.auction.status", "item", live.auctions().shown(auction),
                        "seconds", String.valueOf(live.auctions().secondsLeft(auction)),
                        "amount", currency.render(auction.bid()), "player", auction.bidderName());
            } else {
                live.messages().send(sender, "economy.auction.status-no-bid", "item", live.auctions().shown(auction),
                        "seconds", String.valueOf(live.auctions().secondsLeft(auction)),
                        "amount", currency.render(auction.start()));
            }
        }
        long waiting = all.stream().filter(auction -> !auction.live()).count();
        if (waiting > 0) {
            live.messages().send(sender, "economy.auction.queue-size", "count", String.valueOf(waiting));
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length <= 1) {
            return starting(args.length == 0 ? "" : args[0], List.of("sell", "bid", "cancel", "claim", "mute", "info"));
        }
        if (args[0].equalsIgnoreCase("sell") && args.length >= 3) {
            return starting(args[args.length - 1], List.of("2m", "5m", "10m"));
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "the auction house: selling to the highest bidder, bidding, and picking up what you won";
    }
}
