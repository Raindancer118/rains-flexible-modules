package de.raindancer.modules.names;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.names.command.NameStyleCommand;

import java.util.List;

/**
 * The commands, built at bootstrap and pointed at services that do not exist yet.
 *
 * <h2>Why this class exists at all</h2>
 * Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase — before the plugin object
 * exists, let alone this module's services. A handler registered in {@code onEnable} never runs at all:
 * no warning, no exception, the command simply does not exist. So the handlers must be built early and
 * must not capture anything.
 *
 * <p>Hence the supplier. The command holds a way to <em>ask</em> for the services, and {@link #ready}
 * fills it in when the module enables. Between the two, {@code ModuleCommands.guarded} answers with one
 * red line saying the module has not started rather than a {@link NullPointerException} in the console.
 *
 * <h2>Why the old names still answer</h2>
 * {@code /namecolour} and {@code /namecolor} are what the standalone plugin registered, and people type
 * what they typed last week — including the spelling their keyboard's country uses.
 */
public final class NamesCommands {

    private static volatile NamesServices services;

    private NamesCommands() {
    }

    /**
     * What the module declares at bootstrap.
     *
     * <p>Cheap, repeatable and dependent on nothing — Paper may ask more than once, and it asks before
     * {@code Bukkit.getServer()} answers anything useful.
     */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("namestyle",
                                "How to colour an item's name, and what each dye does on this server",
                                new NameStyleCommand(NamesCommands::require))
                        .aliased("namecolour", "namecolor"));
    }

    /** Called when the module enables, after which the command works. */
    static void ready(NamesServices live) {
        services = live;
    }

    /** Called when it stops, so a command run afterwards refuses rather than using half-shut services. */
    static void stopped() {
        services = null;
    }

    /** Whether the module is up. For the guard, and for a diagnostic that asks why a command refused. */
    public static boolean isRunning() {
        return services != null;
    }

    /**
     * The services, or an exception naming the real problem.
     *
     * <p>Should be unreachable: the command is guarded, so nobody can get this far while the module is
     * not running. If it ever does throw, the message is the useful half — "not started" rather than a
     * null dereference forty frames deep in a menu.
     */
    private static NamesServices require() {
        NamesServices live = services;
        if (live == null) {
            throw new IllegalStateException("the names module is not running");
        }
        return live;
    }
}
