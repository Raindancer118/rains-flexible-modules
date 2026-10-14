package de.raindancer.modules.healthbars;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.healthbars.listener.HealthBarsListener;
import de.raindancer.modules.healthbars.service.HealthBarService;

import java.util.List;

/**
 * Health under players' names and over hurt mobs, each player switching for themselves whether they see it
 * ({@code /healthbars}, {@code /hp}).
 */
public final class HealthBarsModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("healthbars", "Health Bars", "0.1.0")
            .describedAs("Health under players' names and over hurt mobs; each player switches it for themselves")
            .by("Raindancer118");

    private HealthBarService bars;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        SettingsStore<HealthBarsSettings> settings = context.settings(HealthBarsSettings.class, HealthBarsSettings.DEFAULTS);
        context.core().messages().defineFrom(HealthBarsModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(HealthBarsModule.class.getResourceAsStream("messages-serious.yml"));
        bars = new HealthBarService(context.plugin(), settings::current, context.core().vanish());
        context.listener(new HealthBarsListener(bars));
        var timer = Scheduling.globalTimer(context.plugin(), HealthBarService.FOLLOW_TICKS, HealthBarService.FOLLOW_TICKS,
                task -> bars.tick());
        if (timer != null) {
            context.closeWith(timer::cancel);
        }
        HealthBarsCommands.ready(new HealthBarsServices(context.plugin().getServer(), context.core().messages(), bars));
        context.log().info("Health bars are up.");
    }

    @Override
    public List<ModuleCommand> commands() {
        return HealthBarsCommands.declared();
    }

    @Override
    public void disable() {
        if (bars != null) {
            bars.removeAll();
        }
        HealthBarsCommands.stopped();
    }
}
