package de.raindancer.modules.veintoggle;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.veintoggle.listener.VeinListener;
import de.raindancer.modules.veintoggle.listener.VeinUndoListener;
import de.raindancer.modules.veintoggle.rules.UndoRule;
import de.raindancer.modules.veintoggle.rules.VeinRule;
import de.raindancer.modules.veintoggle.service.VeinUndoService;
import de.raindancer.modules.veintoggle.store.RestoredBlocks;
import de.raindancer.modules.veintoggle.store.VeinHistory;
import de.raindancer.modules.veintoggle.util.PermissionNodes;

import org.bukkit.Server;

import java.util.List;

/**
 * Veinminer, on or off per player. Veinminer itself is neither changed nor depended on: its extra
 * blocks are refused for whoever switched it off (see {@link VeinRule}), so its own config — groups,
 * tools, sneaking, the client mod — stays exactly as the owner set it.
 */
public final class VeinToggleModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("veintoggle", "Vein Toggle", "0.2.2")
            .describedAs("Switch Veinminer on or off for yourself with /vein, and undo a vein with /vein undo")
            .by("Raindancer118");

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        SettingsStore<VeinToggleSettings> settings =
                context.settings(VeinToggleSettings.class, VeinToggleSettings.DEFAULTS);
        context.core().messages().defineFrom(VeinToggleModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(VeinToggleModule.class.getResourceAsStream("messages-serious.yml"));
        int registered = PermissionNodes.register(context.plugin().getServer());
        if (registered > 0) {
            context.log().info("{} permission(s) registered.", registered);
        }

        Server server = context.plugin().getServer();
        VeinHistory history = new VeinHistory();
        RestoredBlocks restored = new RestoredBlocks();
        VeinToggleServices services = new VeinToggleServices(context.plugin(), server, context.core(),
                context.log(), context.core().messages(), settings::current, history,
                new VeinUndoService(server, new UndoRule(), history, restored,
                        (player, where) -> context.core().land().verdict(player, where, LandAction.BUILD).orAllow()));
        context.listener(new VeinListener(new VeinRule(), services::wantsVeins,
                player -> {
                    if (settings.current().sayWhenHeldBack()) {
                        player.sendActionBar(services.messages().get("veintoggle.held-back"));
                    }
                },
                System::currentTimeMillis));
        context.listener(new VeinUndoListener(new VeinRule(), history, restored, System::currentTimeMillis,
                server::getCurrentTick));
        VeinToggleCommands.ready(services);

        if (services.veinminerInstalled()) {
            context.log().info("Vein toggle is up: Veinminer is {} for anybody who has not chosen.",
                    settings.current().onByDefault() ? "on" : "off");
        } else {
            // Not an error: the switch is ready the moment Veinminer is added, and holds what people chose.
            context.log().info("Vein toggle is up, but Veinminer is not installed — /vein does nothing until it is.");
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return VeinToggleCommands.declared();
    }

    @Override
    public void disable() {
        VeinToggleCommands.stopped();
    }
}
