package de.raindancer.modules.chained;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.chained.command.ChainCommand;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later.
 *
 * <h2>Why the command cannot simply be handed the services</h2>
 * Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase, which is before any
 * plugin is enabled and long before this module has built anything. A handler registered in
 * {@code onEnable} never runs at all — silently, with no exception and nothing in the log. So the
 * command is built now, pointing at {@link #require}, and {@link #ready} fills the services in when
 * the module actually starts.
 *
 * <p>Until then the host's {@code ModuleCommands.guarded} answers with one line naming the module,
 * which is the state a player reaches three ways: before it starts, after it failed to start, and
 * after it stopped.
 */
public final class ChainedCommands {

    private static volatile ChainedServices services;

    private ChainedCommands() {
    }

    /** The one command. No predecessor plugin, so there is no earlier name that still has to answer. */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("chain",
                                "Manage and watch a chained-together speedrun",
                                new ChainCommand(ChainedCommands::require))
                        .taking("pair <player1> <player2> [max-distance] — chain two players",
                                "unpair <player> — dissolve their pair",
                                "start — begin the run for your pair",
                                "stop — end it early",
                                "reset [seed <value|random>] — throw the map away and make it again",
                                "status — how far apart you may go, and the clock",
                                "admin — the same, as a menu")
                        .needing("rainschained.chain.use"));
    }

    static void ready(ChainedServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    /**
     * The services, or an exception the host's guard turns into one red line.
     *
     * <p>Thrown rather than returning null: a null here would be a {@link NullPointerException} deep
     * inside a menu, which is a stack trace in the console and nothing at all on the player's screen.
     */
    private static ChainedServices require() {
        ChainedServices live = services;
        if (live == null) {
            throw new IllegalStateException("the chained module is not running");
        }
        return live;
    }
}
