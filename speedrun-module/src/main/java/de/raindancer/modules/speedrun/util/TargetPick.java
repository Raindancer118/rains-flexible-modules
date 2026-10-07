package de.raindancer.modules.speedrun.util;

import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * The one player a command argument means, or the reason it does not — so a command answers "they are
 * offline" and "that matches three players" instead of one "nobody" for everything.
 *
 * <p>Wraps {@link PlayerTargets#lookup}: selectors, real names before nicknames, offline players.
 */
public final class TargetPick {

    public enum Problem { NOBODY, OFFLINE, TOO_MANY, SELECTOR_REFUSED }

    /** Where each problem is worded; {@code nobody} gets {@code player}, the others also {@code count}. */
    public record Keys(String nobody, String offline, String tooMany, String selectorRefused) {
    }

    private final PlayerLookup lookup;
    private final OfflinePlayer who;
    private final Problem problem;

    private TargetPick(PlayerLookup lookup, OfflinePlayer who, Problem problem) {
        this.lookup = lookup;
        this.who = who;
        this.problem = problem;
    }

    /** One player who is here now. */
    public static TargetPick online(Server server, CommandSender sender, String typed) {
        return judge(PlayerTargets.lookup(server, sender, typed), true);
    }

    /** One player, here or not. */
    public static TargetPick anyone(Server server, CommandSender sender, String typed) {
        return judge(PlayerTargets.lookup(server, sender, typed), false);
    }

    private static TargetPick judge(PlayerLookup lookup, boolean needOnline) {
        if (lookup.kind() == PlayerLookup.Kind.SELECTOR_REFUSED) {
            return new TargetPick(lookup, null, Problem.SELECTOR_REFUSED);
        }
        if (lookup.isEmpty()) {
            return new TargetPick(lookup, null, Problem.NOBODY);
        }
        if (lookup.matches().size() > 1) {
            return new TargetPick(lookup, null, Problem.TOO_MANY);
        }
        OfflinePlayer only = lookup.matches().getFirst();
        if (needOnline && lookup.singleOnline().isEmpty()) {
            return new TargetPick(lookup, only, Problem.OFFLINE);
        }
        return new TargetPick(lookup, lookup.singleOnline().map(p -> (OfflinePlayer) p).orElse(only), null);
    }

    public boolean ok() {
        return problem == null;
    }

    public Problem problem() {
        return problem;
    }

    /** The match; only meaningful when {@link #ok()}. */
    public OfflinePlayer who() {
        return who;
    }

    /** The match as an online player; only meaningful when {@link #ok()} after {@link #online}. */
    public Player player() {
        return (Player) who;
    }

    public Optional<Player> onlinePlayer() {
        return lookup.singleOnline();
    }

    /** Says what is wrong; false (and silent) when nothing is. */
    public boolean tell(Messages messages, CommandSender sender, Keys keys) {
        if (problem == null) {
            return false;
        }
        String typed = lookup.typed();
        switch (problem) {
            case NOBODY -> messages.send(sender, keys.nobody(), "player", typed);
            case OFFLINE -> messages.send(sender, keys.offline(), "player", PlayerTargets.shownName(who));
            case TOO_MANY -> messages.send(sender, keys.tooMany(), "player", typed,
                    "count", String.valueOf(lookup.matches().size()));
            case SELECTOR_REFUSED -> messages.send(sender, keys.selectorRefused(), "player", typed);
        }
        return true;
    }
}
