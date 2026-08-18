package de.raindancer.modules.speedrun;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.speedrun.util.PermissionNodes;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later.
 *
 * <h2>Why the commands cannot simply be handed the lobby</h2>
 * Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase, before any plugin is
 * enabled and long before this module has built a {@link SpeedrunLobby}. A handler registered in
 * {@code onEnable} never runs at all — silently. So every command is built now, pointing at
 * {@link #require}, and {@link #ready} fills in the real thing once the module actually starts. Same
 * shape as {@code RtpCommands} — see its own javadoc for why.
 */
public final class SpeedrunCommands {

    private static volatile SpeedrunAdminServices services;

    private SpeedrunCommands() {
    }

    /** The four commands this module brings, beyond the compass and the block. */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("lemmemove", "Escape the speedrun movement freeze",
                                new SpeedrunLemmemoveCommand(SpeedrunCommands::require))
                        .needing(PermissionNodes.LEMMEMOVE_SELF)
                        .taking("[player]"),
                ModuleCommand.of("starthere",
                                "Set the point runners are teleported to when a countdown begins",
                                new SpeedrunStartHereCommand(SpeedrunCommands::require))
                        .needing(PermissionNodes.ADMIN),
                ModuleCommand.of("speedrunreset", "Force-end the current run and regenerate the world",
                                new SpeedrunResetCommand(SpeedrunCommands::require))
                        .needing(PermissionNodes.ADMIN)
                        .auditUsage(),
                ModuleCommand.of("speedrunspectate", "Toggle not racing the next speedrun",
                                new SpeedrunSpectateCommand(SpeedrunCommands::require))
                        .needing(PermissionNodes.SPECTATE));
    }

    static void ready(SpeedrunAdminServices live) {
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
    private static SpeedrunAdminServices require() {
        SpeedrunAdminServices live = services;
        if (live == null) {
            throw new IllegalStateException("the speedrun module is not running");
        }
        return live;
    }
}
