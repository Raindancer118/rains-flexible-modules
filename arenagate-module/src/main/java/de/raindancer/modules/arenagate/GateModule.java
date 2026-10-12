package de.raindancer.modules.arenagate;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelListener;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.arenagate.service.GateService;
import de.raindancer.modules.arenagate.util.PermissionNodes;

import java.util.List;

/**
 * The survival server's way to the arena. Shipped through the standard wrapper this is {@code RainsArenaGate}; the
 * arena itself is {@code RainsArena}, on a server of its own behind the same proxy.
 */
public final class GateModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("arenagate", "Arena Gate", "0.1.0")
            .describedAs("/arena sends you to the arena server through the proxy.")
            .by("Raindancer118");

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        SettingsStore<GateSettings> settings = context.settings(GateSettings.class, GateSettings.DEFAULTS);
        context.core().messages().defineFrom(GateModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(GateModule.class.getResourceAsStream("messages-serious.yml"));
        PermissionNodes.register(context.plugin().getServer());
        ProxyTransfer.register(context.plugin());

        Travel travel = new Travel(context.plugin(), context.core().safety(), context.core().audit());
        context.listener(new TravelListener(travel, true));
        GateService gate = new GateService(context.plugin(), context.core().messages(), travel, settings.current());
        settings.onChange(gate::settings);

        GateCommands.ready(new GateServices(context.plugin(), context.core().messages(), gate));
        context.log().info("Arena gate is up: /arena goes to '{}'.", settings.current().arenaOrDefault());
    }

    @Override
    public List<ModuleCommand> commands() {
        return GateCommands.declared();
    }

    @Override
    public void disable() {
        GateCommands.stopped();
    }
}
