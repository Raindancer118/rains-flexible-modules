package de.raindancer.modules.chat;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.chat.command.AnnounceCommand;
import de.raindancer.modules.chat.command.ChatCommand;
import de.raindancer.modules.chat.command.ChatHistoryCommand;

import java.util.List;

/**
 * The commands, built at bootstrap and pointed at services that do not exist yet.
 *
 * <p>Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase — before the plugin
 * object exists, let alone this module's services. So the handlers are built early with a supplier,
 * and {@link #ready} fills it in once the module enables.
 */
public final class ChatCommands {

    private static volatile ChatServices services;

    private ChatCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("chat", "Clears, freezes, or slows down public chat",
                                new ChatCommand(ChatCommands::require))
                        .taking("clear — sends a wall of blank lines",
                                "freeze — toggles whether anybody but staff may talk",
                                "slowmode <seconds|off> — a cooldown for everybody, on top of any "
                                        + "per-player one"),

                ModuleCommand.of("announce", "Broadcasts a banner every online player sees and hears",
                                new AnnounceCommand(ChatCommands::require))
                        .aliased("bc")
                        .taking("<message>"),

                ModuleCommand.of("chathistory", "Shows chat that happened while you were away",
                                new ChatHistoryCommand(ChatCommands::require))
                        .aliased("chatlog")
                        .taking("(nothing) — what you missed since you last left",
                                "<count> — the last count lines, regardless of when you left"));
    }

    /** Called when the module enables, after which the commands work. */
    static void ready(ChatServices live) {
        services = live;
    }

    /** Called when it stops, so a command run afterwards refuses rather than using half-shut services. */
    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static ChatServices require() {
        ChatServices live = services;
        if (live == null) {
            throw new IllegalStateException("the chat module is not running");
        }
        return live;
    }
}
