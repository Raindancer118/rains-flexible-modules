package de.raindancer.modules.worldutils;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.worldutils.listener.LastPositionListener;
import de.raindancer.modules.worldutils.listener.PortalLinkListener;
import de.raindancer.modules.worldutils.service.WorldAdminService;
import de.raindancer.modules.worldutils.service.WorldTravelService;
import de.raindancer.modules.worldutils.store.LastPositions;
import de.raindancer.modules.worldutils.store.ManagedWorldStore;
import de.raindancer.modules.worldutils.util.PermissionNodes;
import org.bukkit.Server;

import java.util.List;

/**
 * Worlds, as a module: moving between them, and making, resetting and deleting them with a chosen seed.
 *
 * <p>Shipped inside {@code RainsWorldUtils} beside the world gate. Nothing here knows the gate is there:
 * the gate answers Core's world entry rules, and this module asks them.
 *
 * <h2>What is deliberately not here</h2>
 * Regenerating, deleting, carrying settings across, the seed history, portal linking, safe ground,
 * teleporting and selectors are all RainsCore's — see {@code WorldAdminService} and
 * {@code WorldTravelService} for the seams.
 */
public final class WorldUtilsModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("worldutils", "World Utils", "0.1.1")
            .describedAs("Switch worlds and dimensions, and create, reset or delete worlds with a "
                    + "chosen seed and a seed history")
            .by("Raindancer118");

    private LogChannel log;
    private Travel travel;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        RainsCore core = context.core();
        SettingsStore<WorldUtilsSettings> settings =
                context.settings(WorldUtilsSettings.class, WorldUtilsSettings.DEFAULTS);

        // Looked up beside this class rather than at "/messages.yml": RainsCore ships one at its own
        // jar root, and join-classpath makes a root lookup a race between two files.
        core.messages().defineFrom(WorldUtilsModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        ManagedWorldStore managed = new ManagedWorldStore(context.dataFolder());
        LastPositions positions = new LastPositions(core.places());
        travel = new Travel(context.plugin(), core.safety(), core.audit());

        WorldTravelService travelling = new WorldTravelService(context.plugin(), server, travel,
                core.safety(), core.worldEntryRules(), core.messages(), positions, settings.current());
        WorldAdminService admin = new WorldAdminService(context.plugin(), server, core.worldRegenerator(),
                core.seedHistory(), managed, positions, core.messages(), log);
        settings.onChange(travelling::settings);
        settings.onChange(admin::settings);

        // Before anything can ask for them: a world this module made is not loaded by Paper after a
        // restart, and /w, the portal links and the players' last positions all expect it to be there.
        int loaded = admin.loadManaged();

        context.listener(new LastPositionListener(positions, settings::current));
        context.listener(new PortalLinkListener(managed, settings::current, server::getWorld));

        WorldUtilsCommands.ready(new WorldUtilsServices(server, core.messages(), context.chat().brand(),
                settings::current, travelling, admin));

        log.info("World Utils is up: {} world(s) made here, {} loaded again.", managed.all().size(), loaded);
    }

    @Override
    public List<ModuleCommand> commands() {
        return WorldUtilsCommands.declared();
    }

    @Override
    public void disable() {
        WorldUtilsCommands.stopped();
        if (travel != null) {
            travel.clear();
        }
    }
}
