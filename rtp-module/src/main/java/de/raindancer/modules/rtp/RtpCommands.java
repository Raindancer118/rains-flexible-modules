package de.raindancer.modules.rtp;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.rtp.command.RtpCommand;
import de.raindancer.modules.rtp.util.PermissionNodes;

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
public final class RtpCommands {

    private static volatile RtpServices services;

    private RtpCommands() {
    }

    /**
     * The one command.
     *
     * <p>{@code wild} and {@code randomtp} answer too — those are the names other plugins have shipped
     * this feature under, and somebody coming from one of them types what they already know.
     */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("rtp", "Go somewhere random in your own world",
                                new RtpCommand(RtpCommands::require))
                        .aliased("wild", "randomtp")
                        .needing(PermissionNodes.USE));
    }

    static void ready(RtpServices live) {
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
    private static RtpServices require() {
        RtpServices live = services;
        if (live == null) {
            throw new IllegalStateException("the rtp module is not running");
        }
        return live;
    }
}
