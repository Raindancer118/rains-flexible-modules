package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.command.AntiCheatCommand;
import de.raindancer.modules.moderation.util.CheatPermissions;
import de.raindancer.modules.api.ModuleCommand;

import java.util.List;

/** Declared at bootstrap, filled in when the module starts. */
public final class AntiCheatCommands {

    private static volatile AntiCheatServices services;

    private AntiCheatCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(ModuleCommand.of("anticheat", "Looks players up in what Grim flagged, across the network",
                        new AntiCheatCommand(AntiCheatCommands::require))
                .aliased("ac", "rac")
                .taking("(nothing) — the suspects screen", "alerts | verbose", "info <player>",
                        "log <player> [page]", "reset <player>", "checks", "status")
                .needing(CheatPermissions.COMMAND));
    }

    public static void ready(AntiCheatServices live) {
        services = live;
    }

    public static void stopped() {
        services = null;
    }

    /** The anti-cheat part, while it runs — for the moderation screens that open its pages. */
    public static java.util.Optional<AntiCheatServices> running() {
        return java.util.Optional.ofNullable(services);
    }

    private static AntiCheatServices require() {
        AntiCheatServices live = services;
        if (live == null) {
            throw new IllegalStateException("the anti-cheat is not running");
        }
        return live;
    }
}
