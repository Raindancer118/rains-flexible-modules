package de.raindancer.modules.ctf;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.ctf.command.CtfCommand;

import java.util.List;

/**
 * Declared at bootstrap, filled in when the module starts — Paper registers commands before any plugin is enabled, so
 * the command points at {@link #require} until then.
 */
public final class CtfCommands {

    private static volatile CtfServices services;

    private CtfCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(ModuleCommand.of("ctf", "Capture the Flag: play, vote, pick a class, see the best",
                        new CtfCommand(CtfCommands::require))
                .aliased("capturetheflag")
                .taking("(nothing) — the menu", "join | leave", "vote | class [name] | side", "watch | follow",
                        "stats [player] | top | looks | challenges", "party invite|accept|leave|kick|list [player]",
                        "admin start | stop | info", "admin map list | setup <file|map> | menu | title <name> [by <author>]",
                        "admin map rebuild <map> | bases <map>", "admin board place|remove"));
    }

    static void ready(CtfServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static CtfServices require() {
        CtfServices live = services;
        if (live == null) {
            throw new IllegalStateException("the Capture the Flag module is not running");
        }
        return live;
    }
}
