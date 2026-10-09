package de.raindancer.modules.jobs;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.jobs.command.JobsCommand;

import java.util.List;

/**
 * Declared at bootstrap, filled in when the module starts — Paper registers commands before any
 * plugin is enabled, so the command points at {@link #require} until then.
 */
public final class JobsCommands {

    private static volatile JobsServices services;

    private JobsCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("jobs", "The job board: the server's goals, and your share of the reward",
                                new JobsCommand(JobsCommands::require))
                        .aliased("goals", "board", "quests")
                        .taking("(nothing) — the board", "list · end <number> · new [kind] · reload — staff")
                        .needing("rainsjobs.use"));
    }

    static void ready(JobsServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static JobsServices require() {
        JobsServices live = services;
        if (live == null) {
            throw new IllegalStateException("the jobs module is not running");
        }
        return live;
    }
}
