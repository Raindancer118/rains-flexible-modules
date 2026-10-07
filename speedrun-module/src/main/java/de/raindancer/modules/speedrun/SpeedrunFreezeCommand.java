package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * The half {@link SpeedrunLemmemoveCommand} and {@link SpeedrunFreezeAgainCommand} share: who a
 * {@code [player]} argument means.
 *
 * <p>Bare, it is whoever typed it. With a name, it is somebody else — gated on
 * {@link PermissionNodes#LEMMEMOVE_OTHERS} rather than {@link PermissionNodes#LEMMEMOVE_SELF}, since
 * that is the form that reaches into another player's race, in either direction: a head start handed
 * out, or one taken back. Console, having nobody of its own, must always name one.
 *
 * <p>Each subclass brings its own branch of {@code speedrun.<key>.*} wording, so the two read as two
 * commands rather than one with an argument — {@code done} said about a release is not the sentence
 * said about undoing one.
 */
abstract class SpeedrunFreezeCommand implements ISpeedrunCommand {

    private final Supplier<SpeedrunAdminServices> services;

    /**
     * @param services asked for when the command runs, never captured — see {@link ISpeedrunCommand} on
     *                 why a command built at bootstrap cannot hold anything the module built
     */
    protected SpeedrunFreezeCommand(Supplier<SpeedrunAdminServices> services) {
        this.services = services;
    }

    /** The wording branch this command speaks out of: {@code speedrun.<messageKey()>.done}, and so on. */
    protected abstract String messageKey();

    /** What to do to the resolved target, and what to say about it. */
    protected abstract void actOn(SpeedrunAdminServices live, CommandSender sender, Player target);

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        SpeedrunAdminServices live = services.get();
        CommandSender sender = source.getSender();

        List<Player> targets;
        if (args.length > 0) {
            if (!sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)) {
                live.messages().send(sender, key("no-permission-for-others"));
                return;
            }
            // A selector may name a whole group: handing out or taking back a head start suits that.
            PlayerLookup found = PlayerTargets.lookup(Bukkit.getServer(), sender, args[0]);
            if (found.kind() == PlayerLookup.Kind.SELECTOR_REFUSED) {
                live.messages().send(sender, "speedrun.command.selector-refused", "player", args[0]);
                return;
            }
            if (found.isEmpty()) {
                live.messages().send(sender, key("player-not-found"), "player", args[0]);
                return;
            }
            if (found.isOfflineOnly()) {
                live.messages().send(sender, "speedrun.command.player-offline",
                        "player", PlayerTargets.shownName(found.matches().getFirst()));
                return;
            }
            targets = found.online();
        } else if (sender instanceof Player player) {
            targets = List.of(player);
        } else {
            live.messages().send(sender, key("console-needs-a-player"));
            return;
        }

        targets.forEach(target -> actOn(live, sender, target));
    }

    /** One line of {@link #messageKey()}'s branch. */
    protected final String key(String leaf) {
        return "speedrun." + messageKey() + "." + leaf;
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length > 1 || !source.getSender().hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)) {
            return List.of();
        }
        return PlayerTargets.suggest(Bukkit.getServer(), source.getSender(), args.length == 0 ? "" : args[0], who -> true);
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.LEMMEMOVE_SELF;
    }
}
