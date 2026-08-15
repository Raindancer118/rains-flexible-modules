package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** {@code /ignore <player>} — toggles blocking their private messages; {@code /ignore list} shows who. */
public final class IgnoreCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public IgnoreCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "blocks or unblocks somebody's private messages";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player who)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        if (args.length == 0) {
            live.messages().send(who, "essentials.usage", "usage", "/ignore <player>|list");
            return;
        }
        if (args[0].equalsIgnoreCase("list")) {
            list(live, who);
            return;
        }
        Optional<OfflinePlayer> found = Players.find(live.server(), args[0]);
        if (found.isEmpty()) {
            live.messages().send(who, "essentials.no-such-player", "player", args[0]);
            return;
        }
        UUID target = found.get().getUniqueId();
        String name = Players.nameOf(found.get());
        if (live.messaging().isIgnoring(who.getUniqueId(), target)) {
            live.messaging().stopIgnoring(who, target);
            live.messages().send(who, "essentials.ignore.stopped", "player", name);
        } else {
            live.messaging().ignore(who, target);
            live.messages().send(who, "essentials.ignore.started", "player", name);
        }
    }

    private void list(EssentialsServices live, Player who) {
        java.util.Set<UUID> ignored = live.store().ignoredBy(who.getUniqueId());
        if (ignored.isEmpty()) {
            live.messages().send(who, "essentials.ignore.none");
            return;
        }
        List<String> names = new ArrayList<>();
        for (UUID id : ignored) {
            names.add(Players.nameOf(live.server().getOfflinePlayer(id)));
        }
        live.messages().send(who, "essentials.ignore.list", "players", String.join(", ", names));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        String typed = args.length == 1 ? args[0] : "";
        List<String> options = new ArrayList<>(Players.suggestions(services.get().server(), typed));
        if ("list".startsWith(typed.toLowerCase(Locale.ROOT))) {
            options.addFirst("list");
        }
        return options;
    }

    @Override
    public String permission() {
        return PermissionNodes.IGNORE;
    }
}
