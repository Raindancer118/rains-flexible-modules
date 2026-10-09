package de.raindancer.modules.jobs;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.SaleStops;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.jobs.listener.FishListener;
import de.raindancer.modules.jobs.service.GoalService;
import de.raindancer.modules.jobs.store.GoalBook;
import de.raindancer.modules.jobs.store.TemplateCatalogue;
import de.raindancer.modules.jobs.util.PermissionNodes;

import java.security.SecureRandom;
import java.util.List;

/**
 * The job board: goals the whole server works on, paid by share. Money goes through Core's {@code Economies};
 * the shop is told what not to sell through Core's {@code SaleStops} — no dependency on the economy module.
 */
public final class JobsModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("jobs", "Jobs", "0.2.0")
            .describedAs("The job board — goals everybody delivers or fishes towards, paid by their share")
            .by("Raindancer118");

    private GoalService goals;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        SettingsStore<JobsSettings> settings = context.settings(JobsSettings.class, JobsSettings.DEFAULTS);
        context.core().messages().defineFrom(JobsModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(JobsModule.class.getResourceAsStream("messages-serious.yml"));
        int registered = PermissionNodes.register(context.plugin().getServer());
        if (registered > 0) {
            context.log().info("{} permission(s) registered.", registered);
        }

        TemplateCatalogue templates = new TemplateCatalogue(new YamlStore(context.dataFolder().resolve("jobs.yml")),
                () -> JobsModule.class.getResourceAsStream("jobs.yml"));
        int kinds = templates.reload();
        templates.problems().forEach(problem -> context.log().warn("jobs.yml: {}", problem));
        GoalBook book = new GoalBook(new YamlStore(context.dataFolder().resolve("goals.yml")));
        book.load();
        if (!book.readable()) {
            context.log().error("goals.yml could not be read. The board is held as it is until it is fixed — "
                    + "saving now would lose every goal and everything handed in.");
        }

        goals = new GoalService(context.plugin().getServer(), templates, book, context.core().messages(),
                context.log(), System::currentTimeMillis, new SecureRandom(), settings.current());
        settings.onChange(goals::settings);
        JobsServices services = new JobsServices(context.plugin(), context.plugin().getServer(), context.core(),
                context.log(), context.core().messages(), context.chat().brand(), settings::current, goals);

        SaleStops.provide(context.plugin(), goals);
        context.closeWith(() -> SaleStops.retract(goals));
        context.listener(new FishListener(services));
        var minutes = Scheduling.globalTimer(context.plugin(), 20L * 5, 20L * 60, task -> goals.tick());
        if (minutes != null) {
            context.closeWith(minutes::cancel);
        }
        context.closeWith(goals::flush);
        JobsCommands.ready(services);
        context.log().info("The job board is up: {} kind(s) of goal, {} on the board.", kinds, book.active().size());
    }

    @Override
    public List<ModuleCommand> commands() {
        return JobsCommands.declared();
    }

    @Override
    public void disable() {
        JobsCommands.stopped();
        if (goals != null) {
            goals.flush();
        }
    }
}
