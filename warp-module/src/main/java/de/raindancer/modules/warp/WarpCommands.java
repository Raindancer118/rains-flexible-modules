package de.raindancer.modules.warp;

import de.raindancer.modules.api.ModuleCommand;

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
public final class WarpCommands {

    private static volatile WarpServices services;

    private WarpCommands() {
    }

    /**
     * The one command.
     *
     * <p>{@code warps} and {@code warplist} answer too — people type the plural, and somebody coming
     * from another plugin types what that one used.
     */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("warp",
                                "Go to a warp, or manage the list of them",
                                new de.raindancer.modules.warp.command.WarpCommand(
                                        WarpCommands::require))
                        .aliased("warps", "warplist")
                        .taking("<name> — go there",
                                "list — every warp you may use",
                                "set <name> — a warp where you stand",
                                "delete <name> — take one away")
                        .needing("rainswarps.warp.use"));
    }

    static void ready(WarpServices live) {
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
    private static WarpServices require() {
        WarpServices live = services;
        if (live == null) {
            throw new IllegalStateException("the warps module is not running");
        }
        return live;
    }
}
