package de.raindancer.modules.farmlimit;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.farmlimit.listener.FarmListener;
import de.raindancer.modules.farmlimit.rules.FarmRule;
import de.raindancer.modules.farmlimit.service.FarmCounter;
import de.raindancer.modules.farmlimit.util.PermissionNodes;

import java.util.List;

/**
 * Keeps animal farms from growing until they eat the server's tick. Animals that exist are never
 * touched; only new ones from breeding and eggs stop where too many already stand.
 */
public final class FarmLimitModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("farmlimit", "Farm Limit", "0.1.0")
            .describedAs("Stops animal farms from growing past a limit, and shows staff the most crowded chunks with /farms")
            .by("Raindancer118");

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        SettingsStore<FarmLimitSettings> settings =
                context.settings(FarmLimitSettings.class, FarmLimitSettings.DEFAULTS);
        context.core().messages().defineFrom(FarmLimitModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(FarmLimitModule.class.getResourceAsStream("messages-serious.yml"));
        int registered = PermissionNodes.register(context.plugin().getServer());
        if (registered > 0) {
            context.log().info("{} permission(s) registered.", registered);
        }

        FarmLimitServices services = new FarmLimitServices(context.plugin().getServer(), context.core().messages(),
                settings::current, new FarmRule(), new FarmCounter());
        context.listener(new FarmListener(services));
        FarmLimitCommands.ready(services);

        FarmLimitSettings now = settings.current();
        context.log().info("Farm limits are {}: {} of one kind, {} animals within {} blocks.",
                now.enabled() ? "on" : "off", now.mostOfOneKind(), now.mostAnimals(), now.radius());
    }

    @Override
    public List<ModuleCommand> commands() {
        return FarmLimitCommands.declared();
    }

    @Override
    public void disable() {
        FarmLimitCommands.stopped();
    }
}
