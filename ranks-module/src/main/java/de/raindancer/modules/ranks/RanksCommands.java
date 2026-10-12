package de.raindancer.modules.ranks;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.ranks.command.RankCommand;

import java.util.List;

/**
 * Declared at bootstrap, filled in when the module starts — Paper registers commands before any
 * plugin is enabled, so the command points at {@link #require} until then.
 */
public final class RanksCommands {

    private static volatile RanksServices services;

    private RanksCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("rank", "Your rank — [OWNER], [DEV]… — and where it shows",
                                new RankCommand(RanksCommands::require))
                        .aliased("ranks")
                        .taking("(nothing) — your ranks and where they show", "info [player]",
                                "wear <rank|none|auto>", "show <chat|nametag|tablist> [on|off]",
                                "give <player> <rank> · take <player> <rank> · list [rank] · reload — staff")
                        .needing("rainsranks.use"));
    }

    static void ready(RanksServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static RanksServices require() {
        RanksServices live = services;
        if (live == null) {
            throw new IllegalStateException("the ranks module is not running");
        }
        return live;
    }
}
