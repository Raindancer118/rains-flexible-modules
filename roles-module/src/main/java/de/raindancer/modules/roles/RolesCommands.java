package de.raindancer.modules.roles;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.roles.command.RoleCommand;

import java.util.List;

/**
 * Declared at bootstrap, filled in when the module starts — Paper registers commands before any
 * plugin is enabled, so the command points at {@link #require} until then.
 */
public final class RolesCommands {

    private static volatile RolesServices services;

    private RolesCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("role", "Pick your role — each pays less in the shop for different things",
                                new RoleCommand(RolesCommands::require))
                        .aliased("roles", "job")
                        .taking("(nothing) — the roles", "<role> — take it", "info [player]",
                                "bypass — staff: change without waiting",
                                "set <player> <role|none> · reset <player> · reload — staff")
                        .needing("rainsroles.use"));
    }

    static void ready(RolesServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static RolesServices require() {
        RolesServices live = services;
        if (live == null) {
            throw new IllegalStateException("the roles module is not running");
        }
        return live;
    }
}
