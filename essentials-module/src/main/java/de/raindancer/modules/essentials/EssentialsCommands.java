package de.raindancer.modules.essentials;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.essentials.command.AfkCommand;
import de.raindancer.modules.essentials.command.IgnoreCommand;
import de.raindancer.modules.essentials.command.MsgCommand;
import de.raindancer.modules.essentials.command.NickCommand;
import de.raindancer.modules.essentials.command.PlayersCommand;
import de.raindancer.modules.essentials.command.ReplyCommand;
import de.raindancer.modules.essentials.command.SeenCommand;
import de.raindancer.modules.essentials.command.SetSpawnCommand;
import de.raindancer.modules.essentials.command.SpawnCommand;
import de.raindancer.modules.essentials.command.UnignoreCommand;

import java.util.List;

/**
 * The commands, built at bootstrap and pointed at services that do not exist yet.
 *
 * <p>Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase — before the plugin
 * object exists, let alone this module's services. So the handlers are built early with a supplier,
 * and {@link #ready} fills it in once the module enables.
 */
public final class EssentialsCommands {

    private static volatile EssentialsServices services;

    private EssentialsCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("spawn", "Teleports you to the server's spawn point",
                        new SpawnCommand(EssentialsCommands::require)),
                ModuleCommand.of("setspawn", "Moves the server's spawn point to where you stand",
                        new SetSpawnCommand(EssentialsCommands::require))
                        .auditUsage(),

                ModuleCommand.of("msg", "Sends somebody a private message",
                                new MsgCommand(EssentialsCommands::require))
                        .aliased("tell", "w", "whisper")
                        .taking("<player> <message>"),
                ModuleCommand.of("r", "Answers whoever last messaged you",
                                new ReplyCommand(EssentialsCommands::require))
                        .aliased("reply")
                        .taking("<message>"),
                ModuleCommand.of("ignore", "Blocks or unblocks somebody's private messages",
                                new IgnoreCommand(EssentialsCommands::require))
                        .taking("<player> — toggles", "list — who you have blocked"),
                ModuleCommand.of("unignore", "Lets somebody message you again, if you had blocked them",
                                new UnignoreCommand(EssentialsCommands::require))
                        .taking("<player>"),

                ModuleCommand.of("seen", "When somebody was last here, and for how long they have played",
                                new SeenCommand(EssentialsCommands::require))
                        .taking("<player>", "(nothing) — opens a list to pick from"),
                ModuleCommand.of("players", "Everybody the server has ever seen, online or not",
                        new PlayersCommand(EssentialsCommands::require)),

                ModuleCommand.of("nick", "Sets, or removes, what you are called instead of your own name",
                                new NickCommand(EssentialsCommands::require))
                        .taking("(nothing) — opens the nickname menu",
                                "set <name> — colour allowed", "<name> — the same, directly",
                                "clear — back to your own name", "off — the same",
                                "blocklist — the blocklist editor, for whoever may manage it"),

                ModuleCommand.of("afk", "Marks you away from the keyboard, or back, right now",
                        new AfkCommand(EssentialsCommands::require)));
    }

    /** Called when the module enables, after which the commands work. */
    static void ready(EssentialsServices live) {
        services = live;
    }

    /** Called when it stops, so a command run afterwards refuses rather than using half-shut services. */
    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static EssentialsServices require() {
        EssentialsServices live = services;
        if (live == null) {
            throw new IllegalStateException("the essentials module is not running");
        }
        return live;
    }
}
