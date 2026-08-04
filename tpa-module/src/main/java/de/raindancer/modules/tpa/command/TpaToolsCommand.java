package de.raindancer.modules.tpa.command;

import de.raindancer.modules.tpa.TpaServices;
import de.raindancer.modules.tpa.util.PermissionNodes;
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
 * {@code /tpcancel}, {@code /tptoggle}, {@code /tpablock} and {@code /tpaunblock}.
 *
 * <p>Four small commands in one class, because each is a handful of lines and four files of argument
 * handling that differ by one call is four places to fix the same thing. Which one this instance is, is
 * the {@link What} it was built with.
 */
public final class TpaToolsCommand implements ITpaCommand {

    /** Which of the four this one is. */
    public enum What {

        /** Give up on a journey, or take back a request. */
        CANCEL,

        /** The blanket switch: whether anybody may ask. */
        TOGGLE,

        BLOCK,

        UNBLOCK
    }

    private final Supplier<TpaServices> services;
    private final What what;

    public TpaToolsCommand(Supplier<TpaServices> services, What what) {
        this.services = services;
        this.what = what;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        TpaServices live = services.get();
        CommandSender sender = source.getSender();

        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "tpa.only-a-player");
            return;
        }
        switch (what) {
            case CANCEL -> live.asking().cancel(player);
            case TOGGLE -> toggle(live, player, args);
            case BLOCK -> block(live, player, args, true);
            case UNBLOCK -> block(live, player, args, false);
        }
    }

    /** With no argument it flips; with one it is set outright, which is what a script wants. */
    private void toggle(TpaServices live, Player player, String[] args) {
        if (args.length == 0) {
            live.prefs().toggle(player);
            return;
        }
        // Boxed, so "not a word I know" is a third answer rather than a boolean that has to be
        // guarded against separately — the version with a default that guessed had to repeat the
        // whole list of words in an if, and the two lists drifted.
        Boolean wanted = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> Boolean.TRUE;
            case "off", "false", "no" -> Boolean.FALSE;
            default -> null;
        };
        if (wanted == null) {
            live.messages().send(player, "tpa.usage.toggle");
            return;
        }
        live.prefs().set(player, wanted);
    }

    /** With no argument the list opens; with one it acts on that person. */
    private void block(TpaServices live, Player player, String[] args, boolean blocking) {
        if (args.length == 0) {
            live.screens().blocked(player);
            return;
        }
        OfflinePlayer them = known(live, player, args[0], blocking);
        if (them == null) {
            live.messages().send(player, "tpa.no-such-player", "player", args[0]);
            return;
        }
        if (blocking) {
            live.prefs().block(player, them);
        } else {
            live.prefs().unblock(player, them);
        }
    }

    /**
     * Somebody by name, without ever asking Mojang.
     *
     * <p>{@code getOfflinePlayer(String)} blocks on a lookup against Mojang, from what on Folia may be
     * a region thread — so it is never called. Blocking looks among people online; unblocking looks
     * among the people already on the list, who may have logged out years ago.
     */
    private static OfflinePlayer known(TpaServices live, Player who, String name, boolean blocking) {
        if (blocking) {
            return live.server().getPlayerExact(name);
        }
        for (UUID blocked : live.prefs().of(who.getUniqueId()).blocked()) {
            if (live.prefs().nameOf(blocked).equalsIgnoreCase(name)) {
                return live.server().getOfflinePlayer(blocked);
            }
        }
        return null;
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (!(source.getSender() instanceof Player player) || args.length > 1) {
            return List.of();
        }
        TpaServices live = services.get();
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> options = switch (what) {
            case TOGGLE -> List.of("on", "off");
            case BLOCK -> live.server().getOnlinePlayers().stream()
                    .filter(other -> !other.equals(player))
                    .map(Player::getName)
                    .toList();
            // Only who they have actually blocked. Completing everybody would suggest names that
            // cannot be unblocked because they never were.
            case UNBLOCK -> live.prefs().of(player.getUniqueId()).blocked().stream()
                    .map(blocked -> live.prefs().nameOf(blocked))
                    .toList();
            case CANCEL -> List.of();
        };
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed))
                .limit(50)
                .toList();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return switch (what) {
            case CANCEL -> "give up on a teleport, or take back your request";
            case TOGGLE -> "whether people may ask to teleport to you";
            case BLOCK -> "stop one person asking you";
            case UNBLOCK -> "let them ask again";
        };
    }
}
