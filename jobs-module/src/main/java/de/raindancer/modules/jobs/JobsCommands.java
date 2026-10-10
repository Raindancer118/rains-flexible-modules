package de.raindancer.modules.jobs;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.jobs.command.JobsCommand;
import de.raindancer.modules.jobs.command.QuestsCommand;

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
                        .aliased("goals", "board")
                        .taking("(nothing) — the board", "list · end <number> · new [kind] · reload — staff")
                        .needing("rainsjobs.use"),
                ModuleCommand.of("quests", "Your personal quests for today, paid as soon as each is done",
                                new QuestsCommand(JobsCommands::require))
                        .aliased("quest", "dailies")
                        .taking("(nothing) — your quests", "give <player> <quest> · reset <player> · reload — staff")
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
