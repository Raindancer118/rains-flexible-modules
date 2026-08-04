package de.raindancer.modules.homes;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.homes.command.DelHomeCommand;
import de.raindancer.modules.homes.command.HomeCommand;
import de.raindancer.modules.homes.command.SetHomeCommand;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later.
 *
 * <h2>Why the commands cannot simply be handed the services</h2>
 * Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase, which is before any plugin
 * is enabled and long before this module has built anything. A handler registered in {@code onEnable}
 * never runs at all — silently, with no exception and nothing in the log. So the commands are built now,
 * pointing at {@link #require}, and {@link #ready} fills the services in when the module actually starts.
 *
 * <p>Until then the host's {@code ModuleCommands.guarded} answers with one line naming the module, which
 * is the state a player reaches three ways: before it starts, after it failed to start, and after it
 * stopped.
 *
 * <h2>The aliases are the old plugin's</h2>
 * {@code /homes} and {@code /removehome} answered before and still do. People type what they typed last
 * week, and a port that dropped an alias would be a port that broke somebody's muscle memory for no
 * reason at all.
 */
public final class HomeCommands {

    private static volatile HomeServices services;

    private HomeCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("home",
                                "Go to one of your homes, or see the list of them",
                                new HomeCommand(HomeCommands::require))
                        .aliased("homes")
                        .taking("<name> — go to that one", "with no name — the list")
                        .needing("rainshomes.home"),
                ModuleCommand.of("sethome",
                                "Save where you are standing as a home",
                                new SetHomeCommand(HomeCommands::require))
                        .taking("<name> — or nothing, for your first")
                        .needing("rainshomes.home"),
                ModuleCommand.of("delhome",
                                "Forget one of your homes",
                                new DelHomeCommand(HomeCommands::require))
                        .aliased("removehome")
                        .taking("<name>")
                        .needing("rainshomes.home"));
    }

    static void ready(HomeServices live) {
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
    private static HomeServices require() {
        HomeServices live = services;
        if (live == null) {
            throw new IllegalStateException("the homes module is not running");
        }
        return live;
    }
}
