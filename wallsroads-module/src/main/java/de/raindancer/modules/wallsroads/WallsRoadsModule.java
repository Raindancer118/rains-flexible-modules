package de.raindancer.modules.wallsroads;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.world.selection.MarkingListener;
import de.raindancer.core.world.selection.MarkingSessions;
import de.raindancer.core.world.selection.MarkingTool;
import de.raindancer.core.world.visual.OutlineRenderer;
import de.raindancer.core.world.visual.SelectionMarkers;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.wallsroads.claims.ClaimIntegration;
import de.raindancer.modules.wallsroads.listener.GateListener;
import de.raindancer.modules.wallsroads.map.MapIntegration;
import de.raindancer.modules.wallsroads.map.MapLink;
import de.raindancer.modules.wallsroads.listener.RoadTravelListener;
import de.raindancer.modules.wallsroads.listener.StructureProtectionListener;
import de.raindancer.modules.wallsroads.claims.ClaimLink;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.screen.RoadEditMenu;
import de.raindancer.modules.wallsroads.screen.WallEditMenu;
import de.raindancer.modules.wallsroads.screen.WallsRoadsConfigMenu;
import de.raindancer.modules.wallsroads.screen.WallsRoadsMenu;
import de.raindancer.modules.wallsroads.util.ManualBook;
import de.raindancer.modules.wallsroads.selection.WallsRoadsSelectionFlow;
import de.raindancer.modules.wallsroads.service.WallsRoadsService;
import de.raindancer.modules.wallsroads.store.Occupancy;
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
    private ModuleContext context;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask curfewTask;
    private SelectionMarkers selectionMarkers = new SelectionMarkers(null);

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        this.context = context;
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

        // Rebuilt from what each structure's snapshot says it covered, rather than kept in a file of
        // its own that could fall out of step with the structures themselves.
        Occupancy occupancy = new Occupancy();
        for (Wall wall : registry.allWalls()) {
            if (wall.isBuilt()) {
                occupancy.claim(wall.id(), wall.snapshot());
            }
        }
        for (RoadPath road : registry.allRoads()) {
            if (road.isBuilt()) {
                occupancy.claim(road.id(), road.snapshot());
            }
        }

        WallsRoadsService service = new WallsRoadsService(context.plugin(), log, registry, storage,
                occupancy, settings.current());
        settings.onChange(service::settings);

        MarkingTool markingTool = new MarkingTool(context.plugin());
        MarkingSessions sessions = new MarkingSessions();
        OutlineRenderer outline = new OutlineRenderer(context.plugin());
        SelectionMarkers markers = new SelectionMarkers(context.plugin());
        selectionMarkers = markers;

        ClaimLink claimLink = ClaimIntegration.tryLink(log);
        MapLink mapLink = MapIntegration.tryLink(log);

        WallsRoadsSelectionFlow selectionFlow = new WallsRoadsSelectionFlow(markingTool, sessions, outline,
                markers, registry, service, context.core().messages(), log, settings::current);
        context.listener(new MarkingListener(markingTool, sessions, selectionFlow));

        services = new WallsRoadsServices(context.plugin(), server, log, context.core().messages(),
                context.chat().brand(), context.core(), settings::current, settings,
                registry, storage, service, markingTool, outline, selectionFlow, new LiveScreens(),
                claimLink, mapLink);

        WallsRoadsCommands.ready(services);

        context.listener(new StructureProtectionListener(services));
        context.listener(new GateListener(services));
        context.listener(new RoadTravelListener(services));
        startCurfewWatch(settings::current, service, server);

        log.info("Walls and Roads are up: {} wall(s), {} road(s) loaded.",
                registry.wallCount(), registry.roadCount());
    }

    /**
     * Watches for the change of day, and tells the service when it turns.
     *
     * <p>On the change rather than on a timer that re-applies the current state: a gate somebody
     * deliberately opened at midnight would otherwise be shut again on the next tick of the timer,
     * and a gate that will not stay open is worse than one that never closes.
     */
    private void startCurfewWatch(java.util.function.Supplier<WallsRoadsSettings> settings,
                                  WallsRoadsService service, Server server) {
        final boolean[] wasNight = {isNight(server)};
        curfewTask = de.raindancer.core.platform.util.Scheduling.globalTimer(
                context.plugin(), 200L, 200L, task -> {
                    if (!settings.get().nightCurfewAllowed()) {
                        return;
                    }
                    boolean night = isNight(server);
                    if (night != wasNight[0]) {
                        wasNight[0] = night;
                        service.applyCurfew(night);
                    }
                });
    }

    /** Night as the mobs reckon it: from dusk, when a gate would actually be worth closing. */
    private static boolean isNight(Server server) {
        return server.getWorlds().stream().findFirst()
                .map(world -> {
                    long time = world.getTime();
                    return time >= 13000 && time < 23000;
                })
                .orElse(false);
    }

    private final class LiveScreens implements IWallsRoadsScreensOpener {

        @Override
        public void list(Player viewer) {
            new WallsRoadsMenu(services, viewer, null).open();
        }

        @Override
        public void wall(Player viewer, Wall wall) {
            new WallEditMenu(services, viewer, wall, null).open();
        }

        @Override
        public void road(Player viewer, RoadPath road) {
            new RoadEditMenu(services, viewer, road, null).open();
        }

        @Override
        public void manual(Player viewer) {
            ManualBook manual = new ManualBook(services.config());
            // Opened every time, given once: the contents depend on what this server allows, so a
            // copy somebody kept from last month can be out of date — and handing out a second one
            // every time somebody reads it fills their inventory with books.
            viewer.openBook(manual.asBook());
            boolean alreadyHasOne = java.util.Arrays.stream(viewer.getInventory().getContents())
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(stack -> stack.getItemMeta() instanceof org.bukkit.inventory.meta.BookMeta book
                            && book.hasTitle()
                            && ManualBook.plain(book.title()).contains(ManualBook.title()));
            if (!alreadyHasOne) {
                de.raindancer.core.content.items.ToolGift.give(viewer, manual.asItem());
            }
        }

        @Override
        public void config(Player viewer) {
            new WallsRoadsConfigMenu(services, viewer, null).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return WallsRoadsCommands.declared();
    }

    @Override
    public void disable() {
        if (curfewTask != null) {
            curfewTask.cancel();
            curfewTask = null;
        }
        // Markers are client-side only, so leaving them behind would show somebody blocks that are
        // not there until they walk far enough for the server to resend the chunk.
        if (services != null) {
            services.outline().stopAll();
        }
        selectionMarkers.clearAll();
        WallsRoadsCommands.stopped();
        // Standing walls and roads are simply left in the world — a module stopping does not mean
        // the town should vanish. The stored records are what re-populates the registry on the next
        // enable(), through WallsRoadsStorage#loadAllWalls/loadAllRoads.
    }
}
