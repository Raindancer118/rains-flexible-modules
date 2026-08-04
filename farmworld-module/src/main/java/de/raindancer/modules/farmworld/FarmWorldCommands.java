package de.raindancer.modules.farmworld;

import de.raindancer.modules.api.ModuleCommand;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later.
 *
 * <h2>Why the command cannot simply be handed the services</h2>
 * Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase, which is before any plugin is
 * enabled and long before this module has built anything. A handler registered in {@code onEnable} never runs
 * at all — silently, with no exception and nothing in the log. So the command is built now, pointing at
 * {@link #require}, and {@link #ready} fills the services in when the module actually starts.
 *
 * <p>Until then the host's {@code ModuleCommands.guarded} answers with one line naming the module, which is the
 * state a player reaches three ways: before it starts, after it failed to start, and after it stopped.
 *
 * <h2>Why {@code farmworld} is only an alias</h2>
 * Because RainsCore ships a {@code /farmworld} of its own — the plain one, with no menu, no warm-up, no scatter
 * and no warnings — as a handler a host may register. Core registers nothing itself, so on a server running
 * this module there is no clash unless the host asks for both, and a host that has this module should not:
 * Paper answers a clash by namespacing the loser, and a server with {@code /farmworld} and
 * {@code /rainsfarmworlds:farmworld} in its help has two commands that do nearly the same thing and one of
 * them is worse.
 */
public final class FarmWorldCommands {

    private static volatile FarmWorldServices services;

    private FarmWorldCommands() {
    }

    /**
     * The one command.
     *
     * <p>{@code farmwelt} answers too. Not a joke: this repository's server is German-speaking, the word people
     * type is the word they say, and an alias costs nothing next to a command nobody finds.
     */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("farm",
                                "Go to a farm world, or manage the ones this server has",
                                new de.raindancer.modules.farmworld.command.FarmWorldCommand(
                                        FarmWorldCommands::require))
                        .aliased("farmworld", "farmwelt", "fw")
                        .taking("<name> — go there",
                                "list — every farm world this server has",
                                "info <name> — what it is and how long it has left",
                                "create <name> [how often] [border] — make one",
                                "regen <name> confirm — throw one away and make it again")
                        .needing("rainsfarmworlds.farm.use"));
    }

    static void ready(FarmWorldServices live) {
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
     * <p>Thrown rather than returning null: a null here would be a {@link NullPointerException} deep inside a
     * menu, which is a stack trace in the console and nothing at all on the player's screen.
     */
    private static FarmWorldServices require() {
        FarmWorldServices live = services;
        if (live == null) {
            throw new IllegalStateException("the farm worlds module is not running");
        }
        return live;
    }
}
