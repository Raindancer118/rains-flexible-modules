package de.raindancer.modules.playerutils.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.profile.ProfileMenu;
import de.raindancer.modules.playerutils.PlayerUtilsServices;
import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /player} — one door onto everything:
 * <ul>
 *   <li>{@code /player} picks somebody;</li>
 *   <li>{@code /player <name>} opens their tools — or, for whoever may not use the tools, their profile, which
 *       is what {@code /player} has always meant on servers with essentials;</li>
 *   <li>{@code /player <name> <action> …} does it straight away: {@code /player Lilly heal},
 *       {@code /player Lilly launch forward 3}.</li>
 * </ul>
 */
public final class PlayerCommand implements IPlayerUtilsCommand {

    private final Supplier<PlayerUtilsServices> services;
    private final Map<Action, ActionCommand> actions = new EnumMap<>(Action.class);

    public PlayerCommand(Supplier<PlayerUtilsServices> services) {
        this.services = services;
        for (Action action : Action.values()) {
            actions.put(action, new ActionCommand(services, action));
        }
    }

    @Override
    public String describe() {
        return "one player's tools, profile, or any action on them";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        PlayerUtilsServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            if (sender instanceof Player viewer) {
                live.screens().choose(viewer);
            } else {
                live.messages().send(sender, "playerutils.usage", "usage", "/player <player> [action …]");
            }
            return;
        }
        if (args.length >= 2) {
            Optional<Action> action = Action.byWord(args[1]);
            if (action.isEmpty()) {
                live.messages().send(sender, "playerutils.player.no-such-action", "action", args[1]);
                return;
            }
            String[] forwarded = new String[args.length - 1];
            forwarded[0] = args[0];
            System.arraycopy(args, 2, forwarded, 1, args.length - 2);
            if (action.get() == Action.NEAR) {
                forwarded = Arrays.copyOfRange(args, 2, args.length);
            }
            actions.get(action.get()).run(sender, forwarded);
            return;
        }
        Optional<OfflinePlayer> found = PlayerTargets.find(live.server(), args[0]);
        if (found.isEmpty()) {
            live.messages().send(sender, "playerutils.nobody", "player", args[0]);
            return;
        }
        OfflinePlayer them = found.get();
        if (!(sender instanceof Player viewer)) {
            if (them.getPlayer() != null) {
                actions.get(Action.STATUS).run(sender, new String[]{args[0]});
            } else {
                live.messages().send(sender, "playerutils.not-online", "player", args[0]);
            }
            return;
        }
        if (them.isOnline() && viewer.hasPermission(PermissionNodes.TOOLS)) {
            live.screens().tools(viewer, them.getUniqueId());
            return;
        }
        new ProfileMenu(viewer, live.brand(), null, them.getUniqueId(), PlayerTargets.shownName(them)).open();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        PlayerUtilsServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0];
            return PlayerTargets.suggestKnown(live.server(), typed, other -> !(sender instanceof Player viewer)
                    || live.core().vanish().canSee(viewer.getUniqueId(), other.getUniqueId()));
        }
        if (args.length == 2) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return Arrays.stream(Action.values())
                    .filter(action -> action != Action.NEAR)
                    .filter(action -> sender.hasPermission(action.othersNode()) || !(sender instanceof Player))
                    .map(Action::word)
                    .filter(word -> word.startsWith(typed))
                    .toList();
        }
        Optional<Action> action = Action.byWord(args[1]);
        if (action.isEmpty()) {
            return List.of();
        }
        String[] forwarded = new String[args.length - 1];
        forwarded[0] = args[0];
        System.arraycopy(args, 2, forwarded, 1, args.length - 2);
        return actions.get(action.get()).suggest(source, forwarded);
    }
}
