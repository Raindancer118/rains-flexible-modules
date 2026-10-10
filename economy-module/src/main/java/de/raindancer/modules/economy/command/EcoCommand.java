package de.raindancer.modules.economy.command;

import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /eco} — staff: give, take, set, reset, freeze, unfreeze, history, and the owner's tools. Every
 * change of somebody's money is written to Core's audit record with who did it.
 */
public final class EcoCommand extends EconomyCommand {

    private static final List<String> SUBCOMMANDS = List.of("give", "take", "set", "reset", "freeze", "unfreeze",
            "history", "menu", "reprice", "draw", "calm", "coin", "leaderboard", "dealer", "auction", "raffle", "giveaway", "tax", "loan", "packs", "health", "fund", "season");

    public EcoCommand(Supplier<EconomyServices> services) {
        super(services);
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("history")) {
            if (!allowed(live, sender, PermissionNodes.HISTORY_OTHERS)) {
                return;
            }
        } else if (!allowed(live, sender, PermissionNodes.ADMIN)) {
            return;
        }
        switch (sub) {
            case "menu" -> player(live, sender).ifPresent(player -> live.screens().admin(player));
            case "reprice" -> Scheduling.global(live.plugin(), () -> live.messages().send(sender,
                    "economy.admin.repriced", "recipes", String.valueOf(live.shop().reprice())));
            case "draw" -> {
                long next = System.currentTimeMillis() + Math.max(1, live.config().drawHours()) * 3_600_000L;
                live.lottery().draw(next);
            }
            case "coin" -> player(live, sender).ifPresent(player -> {
                org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType().isAir() || de.raindancer.modules.economy.store.CashTags.isCash(held)) {
                    live.messages().send(player, "economy.admin.coin-hold");
                    return;
                }
                net.kyori.adventure.key.Key model = held.getData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL);
                boolean ownLook = model == null || model.equals(held.getType().getKey());
                live.store().set("cash.coin-item", held.getType().name());
                live.store().set("cash.coin-model", ownLook ? "" : model.asString());
                live.store().trySave();
                audit(live, sender, "coin", player, held.getType().name());
                live.messages().send(player, "economy.admin.coin", "item",
                        held.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            });
            case "packs" -> packs(live, sender, args);
            case "dealer" -> player(live, sender).ifPresent(player -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("remove")) {
                    live.messages().send(player, live.dealers().remove(player)
                            ? "economy.admin.dealer-removed" : "economy.admin.dealer-none");
                    return;
                }
                var game = de.raindancer.modules.economy.model.DealerGame.read(args.length > 2 ? args[2] : "casino");
                if (game.isEmpty()) {
                    live.messages().send(player, "economy.usage.eco");
                    return;
                }
                live.dealers().place(player, game.get());
                live.messages().send(player, "economy.admin.dealer-placed", "game", game.get().title());
            });
            case "leaderboard" -> player(live, sender).ifPresent(player -> {
                boolean removing = args.length > 1 && args[1].equalsIgnoreCase("remove");
                if (removing) {
                    live.messages().send(player, live.displays().remove(player)
                            ? "economy.admin.leaderboard-removed" : "economy.admin.leaderboard-none");
                } else if (!live.config().leaderboardsEnabled()) {
                    live.messages().send(player, "economy.admin.leaderboards-off");
                } else {
                    live.displays().place(player);
                    live.messages().send(player, "economy.admin.leaderboard-placed");
                }
            });
            case "auction" -> {
                if (args.length > 2 && args[1].equalsIgnoreCase("cancel")) {
                    var picked = live.auctions().pick(args[2]);
                    if (picked.isEmpty()) {
                        live.messages().send(sender, "economy.auction.no-such", "what", args[2]);
                        return;
                    }
                    if (!live.auctions().callOff(picked.get().id())) {
                        live.messages().send(sender, "economy.auction.nothing-running");
                        return;
                    }
                    live.messages().send(sender, "economy.auction.cancelled", "item", picked.get().itemName(),
                            "player", picked.get().sellerName());
                    return;
                }
                if (args.length > 1 && args[1].equalsIgnoreCase("clear")) {
                    live.messages().send(sender, "economy.auction.cleared",
                            "count", String.valueOf(live.auctions().callOffAll()));
                    return;
                }
                var running = live.auctions().live();
                if (running.isEmpty() || !live.auctions().callOff(running.get().id())) {
                    live.messages().send(sender, "economy.auction.nothing-running");
                    return;
                }
                live.messages().send(sender, "economy.auction.stopped");
            }
            case "raffle" -> {
                if (args.length > 2 && args[1].equalsIgnoreCase("cancel")) {
                    RaffleCommand.whole(args, 2).ifPresent(number -> live.raffles().cancel(sender, number, true));
                    return;
                }
                if (args.length < 3) {
                    live.messages().send(sender, "economy.usage.eco");
                    return;
                }
                int minutes = args.length > 3 ? de.raindancer.core.world.time.Times.parse(args[3])
                        .map(length -> (int) Math.max(1, length.toMinutes())).orElse(0) : 0;
                amount(live, sender, args[1]).ifPresent(prize -> amount(live, sender, args[2]).ifPresent(price ->
                        live.raffles().startServer(sender, prize, price, minutes)));
            }
            case "giveaway" -> {
                if (args.length > 2 && args[1].equalsIgnoreCase("cancel")) {
                    RaffleCommand.whole(args, 2).ifPresent(number -> live.raffles().cancel(sender, number, true));
                    return;
                }
                if (args.length < 2) {
                    live.messages().send(sender, "economy.usage.eco");
                    return;
                }
                amount(live, sender, args[1]).ifPresent(prize ->
                        live.raffles().giveServer(sender, prize, GiveawayCommand.minutes(args, 2)));
            }
            case "health" -> Scheduling.async(live.plugin(), () -> {
                var health = live.supply().health();
                Scheduling.global(live.plugin(), () -> SupplyReport.send(live, sender, health));
            });
            case "fund" -> fund(live, sender, args);
            case "season" -> {
                if (!live.supply().current().seasons()) {
                    live.messages().send(sender, "economy.season.off");
                    return;
                }
                if (args.length < 2 || !args[1].equalsIgnoreCase("end")) {
                    live.messages().send(sender, "economy.usage.eco-supply");
                    return;
                }
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    live.messages().send(sender, "economy.season.confirm");
                    return;
                }
                Scheduling.async(live.plugin(), () -> {
                    var ended = live.seasons().end();
                    Scheduling.global(live.plugin(), () -> {
                        if (ended.isEmpty()) {
                            live.messages().send(sender, "economy.season.refused");
                            return;
                        }
                        audit(live, sender, "season", null, "season " + ended.get().season() + " ended");
                        for (org.bukkit.entity.Player online : live.server().getOnlinePlayers()) {
                            live.messages().send(online, "economy.season.ended", "season",
                                    String.valueOf(ended.get().season()), "count", String.valueOf(ended.get().accounts()),
                                    "points", String.valueOf(ended.get().points()));
                        }
                        live.messages().send(sender, "economy.season.ended", "season",
                                String.valueOf(ended.get().season()), "count", String.valueOf(ended.get().accounts()),
                                "points", String.valueOf(ended.get().points()));
                    });
                });
            }
            case "tax" -> {
                if (args.length < 2) {
                    live.messages().send(sender, "economy.usage.eco");
                    return;
                }
                double percent;
                try {
                    percent = Double.parseDouble(args[1].replace("%", "").replace(',', '.'));
                } catch (NumberFormatException notANumber) {
                    live.messages().send(sender, "economy.tax.percent");
                    return;
                }
                live.tax().byHand(sender, percent, args.length > 2 && args[2].equalsIgnoreCase("confirm"));
            }
            case "loan" -> {
                if (args.length < 2) {
                    live.messages().send(sender, "economy.usage.eco");
                    return;
                }
                target(live, sender, args[1]).ifPresent(who -> {
                    String name = PlayerTargets.shownName(who);
                    if (args.length > 2 && args[2].equalsIgnoreCase("forgive")) {
                        live.loans().forgive(who.getUniqueId()).ifPresentOrElse(loan -> {
                            audit(live, sender, "loan-forgiven", who, live.currency().format(loan.owed()));
                            live.messages().send(sender, "economy.loan.forgiven", "player", name,
                                    "owed", live.currency().render(loan.owed()));
                        }, () -> live.messages().send(sender, "economy.loan.none", "player", name));
                        return;
                    }
                    live.loans().loanOf(who.getUniqueId()).ifPresentOrElse(loan ->
                            live.messages().send(sender, "economy.loan.shows", "player", name,
                                    "owed", live.currency().render(loan.owed()),
                                    "borrowed", live.currency().render(loan.borrowed()),
                                    "when", live.loans().dueIn(loan)),
                            () -> live.messages().send(sender, "economy.loan.none", "player", name));
                });
            }
            case "calm" -> {
                live.market().calm();
                live.messages().send(sender, "economy.admin.calmed");
            }
            case "give", "take", "set" -> {
                if (args.length < 3) {
                    live.messages().send(sender, "economy.usage.eco");
                    return;
                }
                target(live, sender, args[1]).ifPresent(who -> (sub.equals("set") ? balance(live, sender, args[2])
                        : amount(live, sender, args[2])).ifPresent(amount ->
                        adjust(live, sender, sub, who, amount, rest(args, 3))));
            }
            case "reset" -> {
                if (args.length < 2) {
                    live.messages().send(sender, "economy.usage.eco");
                    return;
                }
                target(live, sender, args[1]).ifPresent(who ->
                        adjust(live, sender, "set", who, live.config().starting(), "Reset"));
            }
            case "freeze", "unfreeze" -> {
                if (args.length < 2) {
                    live.messages().send(sender, "economy.usage.eco");
                    return;
                }
                target(live, sender, args[1]).ifPresent(who -> {
                    boolean frozen = sub.equals("freeze");
                    if (live.economy().book().freeze(who.getUniqueId(), frozen)) {
                        audit(live, sender, sub, who, "");
                        live.messages().send(sender, frozen ? "economy.admin.frozen" : "economy.admin.unfrozen",
                                "player", PlayerTargets.shownName(who));
                    } else {
                        live.messages().send(sender, "economy.no-account", "player", PlayerTargets.shownName(who));
                    }
                });
            }
            case "history" -> {
                if (args.length < 2) {
                    live.messages().send(sender, "economy.usage.eco");
                    return;
                }
                player(live, sender).ifPresent(player -> target(live, sender, args[1]).ifPresent(who ->
                        live.statements().open(player, who.getUniqueId(), PlayerTargets.shownName(who))));
            }
            default -> live.messages().send(sender, "economy.usage.eco");
        }
    }

    private void adjust(EconomyServices live, CommandSender sender, String how, OfflinePlayer who, Money amount,
                        String reason) {
        UUID id = who.getUniqueId();
        if (!live.economy().hasAccount(id)) {
            live.economy().open(id, who.getName());
        }
        String why = reason.isBlank() ? "By " + sender.getName() : reason + " (" + sender.getName() + ")";
        EconomyResult result = switch (how) {
            case "give" -> live.economy().move(id, amount, TransactionKind.ADMIN, why);
            case "take" -> live.economy().move(id, amount.negate(), TransactionKind.ADMIN, why);
            default -> {
                Money before = live.economy().balance(id);
                EconomyResult set = live.economy().book().set(id, amount, why);
                if (set.succeeded()) {
                    live.economy().tell(id, amount.minus(before), amount, TransactionKind.ADMIN);
                }
                yield set;
            }
        };
        String name = PlayerTargets.shownName(who);
        if (!result.succeeded()) {
            live.messages().send(sender, "economy.admin.refused", "player", name,
                    "reason", result.outcome().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            return;
        }
        audit(live, sender, how, who, live.currency().format(amount) + (reason.isBlank() ? "" : " — " + reason));
        live.messages().send(sender, "economy.admin.done", "player", name,
                "balance", live.currency().render(result.balance()));
    }

    private static void audit(EconomyServices live, CommandSender sender, String action, OfflinePlayer who,
                              String detail) {
        AuditEntry.Builder entry = AuditEntry.of("economy", action);
        if (who != null) {
            entry = entry.to(who.getUniqueId(), PlayerTargets.shownName(who));
        }
        entry = entry.saying(detail);
        if (sender instanceof Player player) {
            entry.by(player.getUniqueId(), player.getName());
        }
        live.core().audit().record(entry);
    }

    /** {@code fund start <name> <target> [effect…]}, {@code fund remove <name>}, {@code fund} alone lists them. */
    private static void fund(EconomyServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            FundCommand.list(live, sender);
            return;
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        if (verb.equals("remove") && args.length > 2) {
            String name = String.join(" ", List.of(args).subList(2, args.length));
            Scheduling.async(live.plugin(), () -> {
                boolean removed = live.funds().remove(name);
                Scheduling.global(live.plugin(), () -> live.messages().send(sender, removed
                        ? "economy.fund.removed" : "economy.fund.no-such", "name", name));
            });
            return;
        }
        if (!verb.equals("start") || args.length < 4) {
            live.messages().send(sender, "economy.usage.eco-supply");
            return;
        }
        String name = args[2];
        String effect = args.length > 4 ? String.join(" ", List.of(args).subList(4, args.length)) : "";
        amount(live, sender, args[3]).ifPresent(target -> Scheduling.async(live.plugin(), () -> {
            var refused = live.funds().create(name, target, effect);
            Scheduling.global(live.plugin(), () -> {
                if (refused.isPresent()) {
                    live.messages().send(sender, refused.get());
                    return;
                }
                audit(live, sender, "fund", null, name + " " + live.currency().format(target) + " " + effect);
                live.messages().send(sender, "economy.fund.started", "name", name, "target",
                        live.currency().render(target));
            });
        }));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (!source.getSender().hasPermission(PermissionNodes.ADMIN)
                && !source.getSender().hasPermission(PermissionNodes.HISTORY_OTHERS)) {
            return List.of();
        }
        if (args.length <= 1) {
            return starting(args.length == 0 ? "" : args[0], SUBCOMMANDS);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("dealer")) {
            return starting(args[1], List.of("place", "remove"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("dealer")) {
            return starting(args[2], java.util.Arrays.stream(de.raindancer.modules.economy.model.DealerGame.values())
                    .map(game -> game.name().toLowerCase(Locale.ROOT)).toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("raffle")) {
            return starting(args[1], List.of("cancel", "10000"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("auction")) {
            return starting(args[1], List.of("stop", "clear", "cancel"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("auction") && args[1].equalsIgnoreCase("cancel")) {
            EconomyServices now = live();
            return now == null ? List.of() : starting(args[2], now.auctions().auctions().stream()
                    .map(de.raindancer.modules.economy.service.AuctionService::shortId).toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("loan")) {
            return starting(args[2], List.of("forgive"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("leaderboard")) {
            return starting(args[1], List.of("place", "remove"));
        }
        return args.length == 2 ? players(source, args[1]) : List.of();
    }

    @Override
    public String describe() {
        return "staff: giving, taking, setting and freezing money, and the owner's tools";
    }

    /** {@code /eco packs} reads packs.yml again; {@code /eco packs give <player> <pack>} hands one out free. */
    private static void packs(EconomyServices live, CommandSender sender, String[] args) {
        if (args.length >= 4 && args[1].equalsIgnoreCase("give")) {
            org.bukkit.entity.Player target = live.server().getPlayerExact(args[2]);
            var pack = live.packs().pack(args[3]);
            if (target == null || pack.isEmpty()) {
                live.messages().send(sender, target == null ? "economy.not-online" : "economy.packs.unknown",
                        "player", args[2]);
                return;
            }
            target.getInventory().addItem(live.packs().item(pack.get())).values()
                    .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
            live.messages().send(sender, "economy.packs.given", "pack", pack.get().title(), "player", target.getName());
            return;
        }
        int count = live.packs().reload();
        live.messages().send(sender, "economy.packs.reloaded", "count", String.valueOf(count));
        live.packs().problems().forEach(problem -> sender.sendMessage(problem));
    }
}
