package de.raindancer.modules.essentials.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.service.NetworkWho;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code /where <player>} — which server somebody is on, and for staff where they stand. Somebody vanished from the
 * sender is as offline here as anywhere else.
 */
public final class WhereCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public WhereCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "which server somebody is on, and where they stand";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            live.messages().send(sender, "essentials.usage", "usage", "/where <player>");
            return;
        }
        if (PlayerTargets.isSelector(args[0])) {
            live.messages().send(sender, "essentials.player.selector-refused", "selector", args[0]);
            return;
        }
        NetworkWho who = live.who();
        Optional<NetworkWho.Found> found = who.find(sender, args[0]);
        if (found.isEmpty()) {
            offline(live, sender, args[0]);
            return;
        }
        NetworkWho.Found there = found.get();
        boolean showsPlace = sender.hasPermission(PermissionNodes.WHERE_LOCATION);
        if (!showsPlace) {
            say(live, sender, there, Optional.empty());
            return;
        }
        who.locate(there).whenComplete((spot, failed) -> say(live, sender, there,
                failed == null ? spot : Optional.empty()));
    }

    private static void say(EssentialsServices live, CommandSender sender, NetworkWho.Found there,
                            Optional<NetworkWho.Spot> spot) {
        String where = live.who().isNetwork() ? there.server() : "";
        String key = there.self() ? "essentials.where.here" : "essentials.where.there";
        if (spot.isPresent()) {
            key += "-at";
            NetworkWho.Spot at = spot.get();
            live.messages().send(sender, key, "player", there.name(), "server", where, "world", at.world(),
                    "x", String.valueOf(at.x()), "y", String.valueOf(at.y()), "z", String.valueOf(at.z()));
            return;
        }
        live.messages().send(sender, key, "player", there.name(), "server", where);
    }

    /** Nobody online by that name: offline if the server knows them, unknown if not. */
    private static void offline(EssentialsServices live, CommandSender sender, String typed) {
        Optional<OfflinePlayer> known = Players.find(live.server(), typed);
        if (known.isPresent()) {
            live.messages().send(sender, "essentials.player.offline", "player", Players.nameOf(known.get()));
            return;
        }
        live.messages().send(sender, "essentials.no-such-player", "player", typed);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        EssentialsServices live = services.get();
        String typed = args.length == 1 ? args[0] : "";
        Set<String> options = new LinkedHashSet<>(Players.suggest(live.server(), source.getSender(), typed, live.core().vanish()));
        live.who().namesElsewhere(source.getSender()).stream()
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(typed.toLowerCase(java.util.Locale.ROOT)))
                .forEach(options::add);
        return options;
    }

    @Override
    public String permission() {
        return PermissionNodes.WHERE;
    }
}
