package de.raindancer.modules.worldgate;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.worldgate.command.WorldGateCommand;
import de.raindancer.modules.worldgate.util.PermissionNodes;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later.
 *
 * <h2>Why the command cannot simply be handed the services</h2>
 * Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase, before any plugin is
 * enabled and long before this module has built anything. A handler registered in {@code onEnable}
 * never runs at all — silently. So the command is built now, pointing at {@link #require}, and
 * {@link #ready} fills the services in once the module actually starts.
 *
 * <p>Until then the host's {@code ModuleCommands.guarded} answers with one line naming the module,
 * which is the state a player reaches three ways: before it starts, after it failed, after it stopped.
 */
public final class WorldGateCommands {

    private static volatile WorldGateServices services;

    private WorldGateCommands() {
    }

    /**
     * The one command.
     *
     * <p>{@code wgate} is for typing; {@code dimensions} is what an admin coming to this fresh would
     * guess it might be called, and both answering costs nothing.
     */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("worldgate", "Lock, drain, close or evacuate the Nether and the End",
                                new WorldGateCommand(WorldGateCommands::require))
                        .aliased("wgate", "dimensions")
                        .taking("status — both dimensions' current state",
                                "lock <nether|end> <drain|close> — stop new arrivals",
                                "open <nether|end> — let people back in",
                                "evacuate <nether|end> — pull everybody there back to the overworld")
                        .needing(PermissionNodes.STATUS));
    }

    static void ready(WorldGateServices live) {
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
     * inside a command instead, which is a stack trace in the console and nothing at all on the
     * player's screen.
     */
    private static WorldGateServices require() {
        WorldGateServices live = services;
        if (live == null) {
            throw new IllegalStateException("the worldgate module is not running");
        }
        return live;
    }
}
