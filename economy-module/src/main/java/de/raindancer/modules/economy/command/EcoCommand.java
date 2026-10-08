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
            "history", "menu", "reprice", "draw", "calm", "coin", "leaderboard");

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
                if (live.lottery().draw(next).isEmpty()) {
                    live.messages().send(sender, "economy.lottery.no-tickets");
                }
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
            case "calm" -> {
                live.market().calm();
                live.messages().send(sender, "economy.admin.calmed");
            }
            case "give", "take", "set" -> {
                if (args.length < 3) {
                    live.messages().send(sender, "economy.usage.eco");
                    return;
                }
                target(live, sender, args[1]).ifPresent(who -> amount(live, sender, args[2]).ifPresent(amount ->
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
        AuditEntry.Builder entry = AuditEntry.of("economy", action).to(who.getUniqueId(), PlayerTargets.shownName(who))
                .saying(detail);
        if (sender instanceof Player player) {
            entry.by(player.getUniqueId(), player.getName());
        }
        live.core().audit().record(entry);
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
        if (args.length == 2 && args[0].equalsIgnoreCase("leaderboard")) {
            return starting(args[1], List.of("place", "remove"));
        }
        return args.length == 2 ? players(source, args[1]) : List.of();
    }

    @Override
    public String describe() {
        return "staff: giving, taking, setting and freezing money, and the owner's tools";
    }
}
