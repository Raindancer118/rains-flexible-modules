package de.raindancer.modules.tpa;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.tpa.command.AnswerCommand;
import de.raindancer.modules.tpa.command.AskCommand;
import de.raindancer.modules.tpa.command.BackCommand;
import de.raindancer.modules.tpa.command.TpaToolsCommand;
import de.raindancer.modules.tpa.model.TpaKind;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later.
 *
 * <h2>Why the commands cannot simply be handed the services</h2>
 * Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase, before any plugin is
 * enabled and long before this module has built anything. A handler registered in {@code onEnable}
 * never runs at all — silently. So the commands are built now, pointing at {@link #require}, and
 * {@link #ready} fills the services in when the module actually starts.
 *
 * <h2>Every alias is the old plugin's</h2>
 * {@code /tpask}, {@code /tphere}, {@code /tpyes}, {@code /tpno}, {@code /tpadeny},
 * {@code /tpacancel}, {@code /tpatoggle}, {@code /tpblock}, {@code /tpunblock}. People type what they
 * typed last week, and a port that quietly dropped one would break somebody's muscle memory for no
 * reason at all.
 *
 * <h2>{@code /back} is declared whatever the settings say</h2>
 * A command cannot be registered conditionally: this list is built at bootstrap, before the settings
 * have been read. So it is always registered and refuses politely when the server has it switched off —
 * which is better anyway, since the alternative is a command that silently does not exist and reads as
 * a broken install.
 */
public final class TpaCommands {

    private static volatile TpaServices services;

    private TpaCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("tpa",
                                "Ask to teleport to somebody",
                                new AskCommand(TpaCommands::require, TpaKind.TO))
                        .aliased("tpask"),
                ModuleCommand.of("tpahere",
                                "Ask somebody to teleport to you",
                                new AskCommand(TpaCommands::require, TpaKind.HERE))
                        .aliased("tphere"),
                ModuleCommand.of("tpaccept",
                                "Accept a teleport request",
                                new AnswerCommand(TpaCommands::require, true))
                        .aliased("tpyes"),
                ModuleCommand.of("tpdeny",
                                "Turn a teleport request down",
                                new AnswerCommand(TpaCommands::require, false))
                        .aliased("tpno", "tpadeny"),
                ModuleCommand.of("tpcancel",
                                "Give up on a teleport, or take back your request",
                                new TpaToolsCommand(TpaCommands::require,
                                        TpaToolsCommand.What.CANCEL))
                        .aliased("tpacancel"),
                ModuleCommand.of("tptoggle",
                                "Whether people may ask to teleport to you",
                                new TpaToolsCommand(TpaCommands::require,
                                        TpaToolsCommand.What.TOGGLE))
                        .aliased("tpatoggle"),
                ModuleCommand.of("tpablock",
                                "Stop one person asking you",
                                new TpaToolsCommand(TpaCommands::require,
                                        TpaToolsCommand.What.BLOCK))
                        .aliased("tpblock"),
                ModuleCommand.of("tpaunblock",
                                "Let them ask again",
                                new TpaToolsCommand(TpaCommands::require,
                                        TpaToolsCommand.What.UNBLOCK))
                        .aliased("tpunblock"),
                ModuleCommand.of("back",
                        "Go back to where you were, or where you died",
                        new BackCommand(TpaCommands::require)));
    }

    static void ready(TpaServices live) {
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
    private static TpaServices require() {
        TpaServices live = services;
        if (live == null) {
            throw new IllegalStateException("the teleport requests module is not running");
        }
        return live;
    }
}
