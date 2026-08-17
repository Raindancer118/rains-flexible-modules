package de.raindancer.modules.wallsroads;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.world.selection.MarkingListener;
import de.raindancer.core.world.selection.MarkingSessions;
import de.raindancer.core.world.selection.MarkingTool;
import de.raindancer.core.world.visual.OutlineRenderer;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.wallsroads.claims.ClaimIntegration;
import de.raindancer.modules.wallsroads.claims.ClaimLink;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.screen.RoadEditMenu;
import de.raindancer.modules.wallsroads.screen.WallEditMenu;
import de.raindancer.modules.wallsroads.screen.WallsRoadsListMenu;
import de.raindancer.modules.wallsroads.selection.WallsRoadsSelectionFlow;
import de.raindancer.modules.wallsroads.service.WallsRoadsService;
import de.raindancer.modules.wallsroads.store.WallsRoadsRegistry;
import de.raindancer.modules.wallsroads.store.WallsRoadsStorage;
import de.raindancer.modules.wallsroads.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.List;

/**
 * Huge polygonal town walls and roads, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsWallsAndRoads}. Everything genuinely
 * domain-specific — what a wall/road/gate/sign <em>is</em>, the corner and gate decisions, the
 * paving policy — lives in {@code model}/{@code service}; everything else (the geometry, the paced
 * block placement and its undo, the marking tool, the outline preview, the menus, the fluent item
 * builder) is RainsCore's, per the plan this module was built from.
 */
public final class WallsRoadsModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("wallsroads", "Walls and Roads", "1.0.0")
            .describedAs("Huge polygonal town walls and roads that cut a real gate where they cross, "
                    + "with renamable signs placed automatically. Every build has a real inverse.")
            .by("Raindancer118");

    private LogChannel log;
    private WallsRoadsServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        SettingsStore<WallsRoadsSettings> settings =
                context.settings(WallsRoadsSettings.class, WallsRoadsSettings.DEFAULTS);

        context.core().messages().defineFrom(
                WallsRoadsModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        WallsRoadsRegistry registry = new WallsRoadsRegistry();
        WallsRoadsStorage storage = new WallsRoadsStorage(context.dataFolder());
        try {
            storage.ensureDirectories();
        } catch (IOException cannotCreate) {
            throw new IllegalStateException("could not create the walls/roads data folders", cannotCreate);
        }
        for (Wall wall : storage.loadAllWalls()) {
            registry.putWall(wall);
        }
        for (RoadPath road : storage.loadAllRoads()) {
            registry.putRoad(road);
        }

        WallsRoadsService service = new WallsRoadsService(context.plugin(), log, registry, storage,
                settings.current());
        settings.onChange(service::settings);

        MarkingTool markingTool = new MarkingTool(context.plugin());
        MarkingSessions sessions = new MarkingSessions();
        OutlineRenderer outline = new OutlineRenderer(context.plugin());

        ClaimLink claimLink = ClaimIntegration.tryLink(log);

        WallsRoadsSelectionFlow selectionFlow = new WallsRoadsSelectionFlow(markingTool, sessions, outline,
                registry, service, context.core().messages(), log, settings::current);
        context.listener(new MarkingListener(markingTool, sessions, selectionFlow));

        services = new WallsRoadsServices(context.plugin(), server, log, context.core().messages(),
                context.chat().brand(), context.core(), settings::current, settings,
                registry, storage, service, markingTool, outline, selectionFlow, new LiveScreens(), claimLink);

        WallsRoadsCommands.ready(services);

        log.info("Walls and Roads are up: {} wall(s), {} road(s) loaded.",
                registry.wallCount(), registry.roadCount());
    }

    private final class LiveScreens implements IWallsRoadsScreensOpener {

        @Override
        public void list(Player viewer) {
            new WallsRoadsListMenu(services, viewer, null).open();
        }

        @Override
        public void wall(Player viewer, Wall wall) {
            new WallEditMenu(services, viewer, wall, null).open();
        }

        @Override
        public void road(Player viewer, RoadPath road) {
            new RoadEditMenu(services, viewer, road, null).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return WallsRoadsCommands.declared();
    }

    @Override
    public void disable() {
        WallsRoadsCommands.stopped();
        // Standing walls and roads are simply left in the world — a module stopping does not mean
        // the town should vanish. The stored records are what re-populates the registry on the next
        // enable(), through WallsRoadsStorage#loadAllWalls/loadAllRoads.
    }
}
