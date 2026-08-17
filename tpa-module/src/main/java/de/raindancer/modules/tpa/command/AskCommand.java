package de.raindancer.modules.tpa.command;

import de.raindancer.modules.tpa.TpaServices;
import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
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

        // By name, among people who are online. Never getOfflinePlayer(String), which blocks on a
        // lookup against Mojang from what on Folia may be a region thread — and there is nothing to ask
        // of somebody who is not here anyway.
        Player them = live.server().getPlayerExact(args[0]);
        if (them == null || !them.isOnline()) {
            live.messages().send(player, "tpa.no-such-player", "player", args[0]);
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
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        TpaServices live = services.get();
        return live.server().getOnlinePlayers().stream()
                .filter(other -> !other.equals(player))
                .filter(other -> live.core().vanish().canSee(player.getUniqueId(), other.getUniqueId()))
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                .limit(50)
                .toList();
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
