package de.raindancer.modules.tpa.command;

import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.modules.tpa.TpaServices;
import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.util.PermissionNodes;
import de.raindancer.modules.tpa.util.Who;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /tpa} and {@code /tpahere} — asking somebody.
 *
 * <p>One class for both, because they are one request with two answers to "who travels". Two classes
 * would be two copies of the same argument handling, and the copy nobody looks at is the one where the
 * direction ends up backwards.
 *
 * <p>Bare {@code /tpa} opens the hub. Bare {@code /tpahere} does not: it would open the same hub, which
 * is the same page reached two ways with no way to tell which direction was meant.
 */
public final class AskCommand implements ITpaCommand {

    private final Supplier<TpaServices> services;
    private final TpaKind kind;

    public AskCommand(Supplier<TpaServices> services, TpaKind kind) {
        this.services = services;
        this.kind = kind;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        TpaServices live = services.get();
        CommandSender sender = source.getSender();

        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "tpa.only-a-player");
            return;
        }
        if (args.length == 0) {
            if (kind == TpaKind.TO) {
                live.screens().hub(player);
            } else {
                live.messages().send(player, "tpa.usage.ask-here");
            }
            return;
        }

        if (kind == TpaKind.HERE && args[0].equalsIgnoreCase("@a")) {
            if (!player.hasPermission(PermissionNodes.HERE_ALL)) {
                live.messages().send(player, "tpa.no-permission-everyone");
                return;
            }
            live.asking().askEveryone(player);
            return;
        }

        if (de.raindancer.core.platform.command.PlayerGroups.isGroup(args[0])) {
            // A group — a guild's @TAG: one request to each member who is here and whom the asker can see.
            List<Player> members = de.raindancer.core.platform.command.PlayerTargets
                    .resolve(live.server(), player, args[0]).stream()
                    .filter(member -> !member.equals(player) && player.canSee(member)).toList();
            if (members.isEmpty()) {
                live.messages().send(player, "tpa.group-nobody", "group", args[0]);
                return;
            }
            members.forEach(member -> live.asking().ask(player, member, kind));
            live.messages().send(player, "tpa.group-asked", "group", args[0], "count", String.valueOf(members.size()));
            return;
        }

        // Never getOfflinePlayer(String), which blocks on a lookup against Mojang from what on Folia may be a
        // region thread. Somebody offline is found by the server's cache and told apart from a typo.
        PlayerLookup found = PlayerTargets.lookup(live.server(), player, args[0]);
        if (found.singleOnline().isEmpty() && found.matches().size() <= 1
                && found.kind() != PlayerLookup.Kind.SELECTOR && found.kind() != PlayerLookup.Kind.SELECTOR_REFUSED) {
            // Not on this server: perhaps on another one of the network, by the name they have or the name
            // of the player a nickname was.
            String name = found.single().map(OfflinePlayer::getName).filter(java.util.Objects::nonNull).orElse(args[0]);
            Optional<Presence.Here> there = live.asking().findElsewhere(name);
            if (there.isPresent()) {
                live.asking().askElsewhere(player, there.get(), kind);
                return;
            }
        }
        Player them = Who.online(live.server(), live.messages(), player, args[0]).orElse(null);
        if (them == null) {
            return;
        }
        live.asking().ask(player, them, kind);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (!(source.getSender() instanceof Player player) || args.length > 1) {
            return List.of();
        }
        TpaServices live = services.get();
        String typed = args.length == 0 ? "" : args[0];
        List<String> names = new java.util.ArrayList<>(Who.suggest(live.server(), player, typed,
                other -> !other.equals(player)
                        && live.core().vanish().canSee(player.getUniqueId(), other.getUniqueId())));
        live.asking().playersElsewhere().stream().map(Presence.Here::name)
                .filter(name -> name.regionMatches(true, 0, typed, 0, typed.length()))
                .filter(name -> !names.contains(name)).forEach(names::add);
        if (kind == TpaKind.HERE && player.hasPermission(PermissionNodes.HERE_ALL)
                && "@a".startsWith(typed.toLowerCase(Locale.ROOT))) {
            names.add("@a");
        }
        return names;
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return kind == TpaKind.TO
                ? "ask to teleport to somebody"
                : "ask somebody to teleport to you";
    }
}
