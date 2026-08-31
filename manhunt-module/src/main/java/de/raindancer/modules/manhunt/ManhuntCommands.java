package de.raindancer.modules.manhunt;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.manhunt.command.ManhuntCommand;
import de.raindancer.modules.manhunt.command.WhitelistCommand;

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
                ModuleCommand.of("manhunt",
                                "Join a side, start and stop a hunt, and throw chaos at one",
                                new ManhuntCommand(ManhuntCommands::require))
                        .taking("join <runner|hunter> — put yourself on a side",
                                "leave — take yourself off whichever side you were on",
                                "assign <player> <runner|hunter> — put somebody else on a side (admin)",
                                "start — begin the hunt",
                                "stop — end it early",
                                "reset [seed <value|random>] — throw the map away and make it again",
                                "status — the roster and the clock",
                                "chaos [<action>] — throw a chaos action at a running hunt, or open the menu",
                                "setlobby — place the waiting lobby where you are standing (admin)")
                        .needing("rainsmanhunt.manhunt.use"),
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

    public static boolean isRunning() {
        return services != null;
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
