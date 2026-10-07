package de.raindancer.modules.playerutils;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.playerutils.command.ActionCommand;
import de.raindancer.modules.playerutils.command.PlayerCommand;
import de.raindancer.modules.playerutils.command.SpectateCommand;
import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.util.PermissionNodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What the module declares at bootstrap: one command per action, /spectate, and /player over all of them. */
public final class PlayerUtilsCommands {

    private static volatile PlayerUtilsServices services;

    /** Second names that read naturally — and /hurt, which moderation used to own. */
    private static final Map<Action, List<String>> ALIASES = Map.of(
            Action.DAMAGE, List.of("hurt"),
            Action.EXTINGUISH, List.of("ext"),
            Action.IGNITE, List.of("burn"),
            Action.LAUNCH, List.of("yeet"),
            Action.EXPLODE, List.of("boom"),
            Action.SCALE, List.of("size"),
            Action.POSITION, List.of("pos", "coords", "whereis"),
            Action.BREATHE, List.of("air"),
            Action.STATUS, List.of("pstatus"));

    private PlayerUtilsCommands() {
    }

    public static List<ModuleCommand> declared() {
        List<ModuleCommand> commands = new ArrayList<>();
        for (Action action : Action.values()) {
            if (action == Action.SPECTATE) {
                continue;
            }
            ModuleCommand command = ModuleCommand.of(action.word(), action.describe(),
                            new ActionCommand(PlayerUtilsCommands::require, action))
                    .taking(ActionCommand.usage(action).substring(action.word().length() + 1).strip())
                    .needing(action.node());
            List<String> aliases = ALIASES.get(action);
            if (aliases != null) {
                command = command.aliased(aliases.toArray(String[]::new));
            }
            commands.add(command);
        }
        commands.add(ModuleCommand.of("spectate", Action.SPECTATE.describe(),
                        new SpectateCommand(PlayerUtilsCommands::require))
                .aliased("watch")
                .taking("<player>", "stop")
                .needing(Action.SPECTATE.node()));
        commands.add(ModuleCommand.of("player", "One player's tools, profile, or any action on them",
                        new PlayerCommand(PlayerUtilsCommands::require))
                .aliased("playertools")
                .taking("[player] [action …]"));
        return List.copyOf(commands);
    }

    static void ready(PlayerUtilsServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static PlayerUtilsServices require() {
        PlayerUtilsServices live = services;
        if (live == null) {
            throw new IllegalStateException("the playerutils module is not running");
        }
        return live;
    }

    /** The node a /player page needs; here so the descriptor test can find every node in one place. */
    static String toolsNode() {
        return PermissionNodes.TOOLS;
    }
}
