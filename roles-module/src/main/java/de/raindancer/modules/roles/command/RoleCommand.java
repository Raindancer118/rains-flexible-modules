package de.raindancer.modules.roles.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.text.Markup;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.roles.RolesServices;
import de.raindancer.modules.roles.model.Role;
import de.raindancer.modules.roles.screen.RoleMenu;
import de.raindancer.modules.roles.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /role} opens the roles; {@code /role <role>} takes one; {@code /role info [player]} says what
 * somebody is. {@code buy}, {@code rent} and {@code cancel <role>} pay for roles that cost something. Staff: {@code bypass}, {@code set <player> <role|none>}, {@code reset <player>}, {@code reload}.
 */
public final class RoleCommand implements IRolesCommand {

    private final Supplier<RolesServices> services;

    public RoleCommand(Supplier<RolesServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        RolesServices live = services.get();
        CommandSender sender = source.getSender();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "" -> asPlayer(live, sender).ifPresent(player -> new RoleMenu(live, player, null).open());
            case "info" -> info(live, sender, args.length > 1 ? args[1] : null);
            case "bypass" -> {
                if (!sender.hasPermission(PermissionNodes.BYPASS)) {
                    live.messages().send(sender, "roles.usage");
                    return;
                }
                asPlayer(live, sender).ifPresent(player -> live.messages().send(player,
                        live.roles().toggleBypass(player.getUniqueId()) ? "roles.bypass-on" : "roles.bypass-off"));
            }
            case "buy", "rent", "cancel" -> {
                if (args.length < 2) {
                    live.messages().send(sender, "roles.usage-buy");
                    return;
                }
                Optional<Role> role = live.roles().role(args[1]);
                if (role.isEmpty()) {
                    live.messages().send(sender, "roles.unknown", "name", args[1]);
                    return;
                }
                asPlayer(live, sender).ifPresent(player -> {
                    if (first.equals("cancel")) {
                        live.purchases().cancel(player, role.get());
                    } else {
                        new RoleMenu(live, player, null).confirmPurchase(role.get(), first.equals("rent"));
                    }
                });
            }
            case "set" -> staff(live, sender, args, 3).ifPresent(target -> {
                String wanted = args[2].toLowerCase(Locale.ROOT);
                Optional<Role> role = live.roles().role(wanted);
                if (role.isEmpty() && !wanted.equals("none")) {
                    live.messages().send(sender, "roles.unknown", "name", args[2]);
                    return;
                }
                if (!live.roles().set(target.getUniqueId(), role)) {
                    live.messages().send(sender, "roles.not-saved");
                    return;
                }
                String name = PlayerTargets.shownName(target);
                if (role.isEmpty()) {
                    live.messages().send(sender, "roles.cleared", "player", name);
                } else {
                    live.messages().send(sender, "roles.set", "player", name, "article", role.get().article(), "role", new Markup(role.get().coloured()));
                    Player online = target.getPlayer();
                    if (online != null && !online.equals(sender)) {
                        live.messages().send(online, "roles.set-you", "article", role.get().article(), "role", new Markup(role.get().coloured()));
                    }
                }
            });
            case "reset" -> staff(live, sender, args, 2).ifPresent(target -> live.messages().send(sender,
                    live.roles().endWait(target.getUniqueId()) ? "roles.reset" : "roles.not-saved",
                    "player", PlayerTargets.shownName(target)));
            case "reload" -> {
                if (!sender.hasPermission(PermissionNodes.ADMIN)) {
                    live.messages().send(sender, "roles.usage");
                    return;
                }
                live.messages().send(sender, "roles.reloaded", "count", String.valueOf(live.roles().reload()));
                live.roles().problems().forEach(sender::sendMessage);
            }
            default -> {
                Optional<Role> role = live.roles().role(first);
                if (role.isEmpty()) {
                    live.messages().send(sender, "roles.unknown", "name", args[0]);
                    return;
                }
                asPlayer(live, sender).ifPresent(player -> live.roles().choose(player, role.get()));
            }
        }
    }

    private static Optional<Player> asPlayer(RolesServices live, CommandSender sender) {
        if (sender instanceof Player player) {
            return Optional.of(player);
        }
        live.messages().send(sender, "roles.only-a-player");
        return Optional.empty();
    }

    private static Optional<OfflinePlayer> staff(RolesServices live, CommandSender sender, String[] args, int needed) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "roles.usage");
            return Optional.empty();
        }
        if (args.length < needed) {
            live.messages().send(sender, "roles.usage-staff");
            return Optional.empty();
        }
        Optional<OfflinePlayer> target = PlayerTargets.find(live.server(), args[1]);
        if (target.isEmpty()) {
            live.messages().send(sender, "roles.never-here", "player", args[1]);
        }
        return target;
    }

    private static void info(RolesServices live, CommandSender sender, String who) {
        OfflinePlayer target;
        if (who == null) {
            Optional<Player> self = asPlayer(live, sender);
            if (self.isEmpty()) {
                return;
            }
            target = self.get();
        } else {
            if (!sender.hasPermission(PermissionNodes.ADMIN)) {
                live.messages().send(sender, "roles.usage");
                return;
            }
            Optional<OfflinePlayer> found = PlayerTargets.find(live.server(), who);
            if (found.isEmpty()) {
                live.messages().send(sender, "roles.never-here", "player", who);
                return;
            }
            target = found.get();
        }
        String name = PlayerTargets.shownName(target);
        Optional<Role> role = live.roles().roleOf(target.getUniqueId());
        if (role.isEmpty()) {
            live.messages().send(sender, "roles.info-none", "player", name);
            return;
        }
        live.messages().send(sender, "roles.info", "player", name, "article", role.get().article(), "role", new Markup(role.get().coloured()));
        role.get().perks().forEach(perk -> live.messages().send(sender, "roles.info-perk",
                "perk", perk.says(live.roles().perkNow(target.getUniqueId(), perk.percent())),
                "full", String.valueOf(Math.abs(perk.percent()))));
        role.get().abilities().forEach(ability -> live.messages().send(sender, "roles.info-perk",
                "perk", ability.says(live.roles().abilityNow(target.getUniqueId(), ability)),
                "full", String.valueOf(ability.percent())));
        Duration left = live.roles().left(target.getUniqueId());
        if (left.isZero()) {
            live.messages().send(sender, "roles.info-change-now");
        } else {
            live.messages().send(sender, "roles.info-change", "left", Times.describe(left));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        RolesServices live = services.get();
        boolean admin = sender.hasPermission(PermissionNodes.ADMIN);
        if (args.length <= 1) {
            options.add("info");
            options.addAll(List.of("buy", "rent", "cancel"));
            live.roles().roles().forEach(role -> options.add(role.id()));
            if (sender.hasPermission(PermissionNodes.BYPASS)) {
                options.add("bypass");
            }
            if (admin) {
                options.addAll(List.of("set", "reset", "reload"));
            }
        } else if (args.length == 2 && List.of("buy", "rent", "cancel").contains(args[0].toLowerCase(Locale.ROOT))) {
            live.roles().roles().stream().filter(role -> role.forSale()).forEach(role -> options.add(role.id()));
        } else if (args.length == 2 && admin && List.of("set", "reset", "info").contains(args[0].toLowerCase(Locale.ROOT))) {
            live.server().getOnlinePlayers().forEach(player -> options.add(player.getName()));
        } else if (args.length == 3 && admin && args[0].equalsIgnoreCase("set")) {
            options.add("none");
            live.roles().roles().forEach(role -> options.add(role.id()));
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return PermissionNodes.USE;
    }
}
