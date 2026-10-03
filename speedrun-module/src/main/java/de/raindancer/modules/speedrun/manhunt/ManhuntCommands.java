package de.raindancer.modules.speedrun.manhunt;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.speedrun.manhunt.command.ManhuntCommand;
import de.raindancer.modules.speedrun.manhunt.command.WhitelistCommand;
import de.raindancer.modules.speedrun.manhunt.util.PermissionNodes;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later — see {@code ChainedCommands}'
 * own javadoc for why the command is built now, pointing at {@link #require}, rather than handed the
 * services directly.
 */
public final class ManhuntCommands {

    private static volatile ManhuntServices services;

    private ManhuntCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("manhunt", "Pick a side for the next hunt",
                                new ManhuntCommand(ManhuntCommands::require))
                        .taking("join <runner|hunter> — put yourself on a side",
                                "leave — off the Runners; mid-hunt, out of the hunt entirely",
                                "assign <player> <runner|hunter> — put somebody else on a side (admin)",
                                "status — who is on which side, and how the hunt is going",
                                "give <player|all> [tracker|team|structure] — lost compasses back (admin)",
                                "goal remove — no goal, also mid-hunt (admin)",
                                "start — start a hunt, as the lobby's start block does (admin)",
                                "resume [time] — pick a hunt up after a restart, nobody moved or cleared (admin)")
                        .needing(PermissionNodes.USE),
                ModuleCommand.of("whitelist",
                                "Open and close the server whitelist for a hunt; everything else "
                                        + "passes through to vanilla",
                                new WhitelistCommand(ManhuntCommands::require))
                        .taking("open — anybody can join",
                                "close — only whoever is online right now stays whitelisted",
                                "<anything else> — passed straight to vanilla's own /whitelist"));
    }

    static void ready(ManhuntServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    /**
     * The services, or an exception the host's guard turns into one red line — see
     * {@code ChainedCommands.require} for why this throws rather than returning null.
     */
    private static ManhuntServices require() {
        ManhuntServices live = services;
        if (live == null) {
            throw new IllegalStateException("the manhunt module is not running");
        }
        return live;
    }
}
