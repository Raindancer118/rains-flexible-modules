package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.build.BukkitGround;
import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.modules.wallsroads.WallsRoadsSettings;
import de.raindancer.modules.wallsroads.model.Gate;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.RoadSegment;
import de.raindancer.modules.wallsroads.model.RoadSign;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.store.Occupancy;
import de.raindancer.modules.wallsroads.store.WallsRoadsRegistry;
import de.raindancer.modules.wallsroads.store.WallsRoadsStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What actually happens when a wall or road is built, torn down, reshaped or removed.
 *
 * <p>Every path through here obeys two rules that were each learned from a real failure:
 *
 * <ul>
 *   <li><b>Nothing is placed in one tick.</b> Sealing a gate used to run its whole queue at once,
 *       which on a tall wall crossed by several roads is exactly the freeze the rest of this module
 *       is carefully paced to avoid.</li>
 *   <li><b>Nothing builds over somebody else's structure.</b> {@link Occupancy} filters the queue,
 *       so a crossing belongs to whichever road got there first and tearing up the second cannot
 *       punch a hole through the first.</li>
 * </ul>
 */
public final class WallsRoadsService {

    private final Plugin plugin;
    private final LogChannel log;
    private final WallsRoadsRegistry registry;
    private final WallsRoadsStorage storage;
    private final Occupancy occupancy;
    private final WallBuildService wallBuild = new WallBuildService();
    private final RouteProfiler profiler = new RouteProfiler();
    private final RoadBuilder roadBuilder = new RoadBuilder();
    private final GateService gates = new GateService();
    private final SignService signs = new SignService();
    private volatile WallsRoadsSettings settings;

    public WallsRoadsService(Plugin plugin, LogChannel log, WallsRoadsRegistry registry,
                             WallsRoadsStorage storage, Occupancy occupancy, WallsRoadsSettings settings) {
        this.plugin = plugin;
        this.log = log;
        this.registry = registry;
        this.storage = storage;
        this.occupancy = occupancy;
        this.settings = settings;
    }

    public void settings(WallsRoadsSettings updated) {
        this.settings = updated;
    }

    public WallsRoadsRegistry registry() {
        return registry;
    }

    public Occupancy occupancy() {
        return occupancy;
    }

    public RouteProfiler.Rules routeRules() {
        return settings.routeRules();
    }

    /** The route as it would be built right now — what a preview and a material estimate both ask for. */
    public List<RoadSegment> profile(RoadPath road) {
        Ground ground = BukkitGround.of(road.world());
        return ground == null ? List.of() : profiler.profile(road, ground, routeRules());
    }

    // ------------------------------------------------------------------------------------ walls

    public void buildWall(Wall wall, Runnable onDone) {
        Ground ground = BukkitGround.of(wall.world());
        if (ground == null) {
            onDone.run();
            return;
        }
        for (RoadPath road : registry.roadsIn(wall.world())) {
            if (!road.isBuilt()) {
                continue;
            }
            for (Gate gate : gates.detect(wall, road, settings.gateHeight())) {
                wall.putGate(gate);
            }
        }
        Set<ColumnPolygon.Column> openings = wallBuild.openGateColumns(wall);
        BatchBuilder builder = new BatchBuilder(ground,
                occupancy.filter(wallBuild.buildPlacements(wall, openings), wall.id()));
        paced(wall.world(), builder, () -> {
            wall.markBuilt(builder.snapshotSoFar());
            occupancy.claim(wall.id(), wall.snapshot());
            storage.saveWall(wall);
            onDone.run();
        });
    }

    public void teardownWall(Wall wall, Runnable onDone) {
        Ground ground = BukkitGround.of(wall.world());
        if (ground == null) {
            onDone.run();
            return;
        }
        BatchBuilder teardown = wallBuild.newTeardown(ground, wall);
        paced(wall.world(), teardown, () -> {
            wall.markTornDown();
            occupancy.release(wall.id());
            storage.saveWall(wall);
            onDone.run();
        });
    }

    public void deleteWall(Wall wall, Runnable onDone) {
        Runnable finish = () -> {
            registry.removeWall(wall.id());
            occupancy.release(wall.id());
            storage.deleteWall(wall.id());
            onDone.run();
        };
        if (wall.isBuilt()) {
            teardownWall(wall, finish);
        } else {
            finish.run();
        }
    }

    /** Sharp-to-rounded or back, the reshape doing a real teardown-then-rebuild when it was standing. */
    public void reshapeWall(Wall wall, Runnable onDone) {
        boolean wasBuilt = wall.isBuilt();
        if (wasBuilt) {
            teardownWall(wall, () -> buildWall(wall, onDone));
        } else {
            storage.saveWall(wall);
            onDone.run();
        }
    }

    public void sealGate(Wall wall, String gateId, Runnable onDone) {
        wall.gate(gateId).ifPresentOrElse(gate -> {
            Ground ground = BukkitGround.of(wall.world());
            if (ground == null || gate.sealed()) {
                onDone.run();
                return;
            }
            BatchBuilder seal = new BatchBuilder(ground, occupancy.filter(
                    wallBuild.sealPlacements(wall, new LinkedHashSet<>(gate.openingColumns())), wall.id()));
            paced(wall.world(), seal, () -> {
                wall.replaceGate(gate.asSealed(true));
                storage.saveWall(wall);
                onDone.run();
            });
        }, onDone);
    }

    public void reopenGate(Wall wall, String gateId, Runnable onDone) {
        wall.gate(gateId).ifPresentOrElse(gate -> {
            Ground ground = BukkitGround.of(wall.world());
            if (ground == null || !gate.sealed()) {
                onDone.run();
                return;
            }
            BatchBuilder cut = new BatchBuilder(ground, wallBuild.cutPlacements(wall,
                    new LinkedHashSet<>(gate.openingColumns()), gate.height()));
            paced(wall.world(), cut, () -> {
                wall.replaceGate(gate.asSealed(false));
                storage.saveWall(wall);
                onDone.run();
            });
        }, onDone);
    }

    // ------------------------------------------------------------------------------------ roads

    public void buildRoad(RoadPath road, Runnable onDone) {
        Ground ground = BukkitGround.of(road.world());
        if (ground == null) {
            onDone.run();
            return;
        }
        List<RoadSegment> plan = profiler.profile(road, ground, routeRules());
        BatchBuilder builder = new BatchBuilder(ground,
                occupancy.filter(roadBuilder.placements(road, plan, ground), road.id()));
        paced(road.world(), builder, () -> {
            road.markBuilt(builder.snapshotSoFar());
            occupancy.claim(road.id(), road.snapshot());
            cutThroughStandingWalls(road, ground);
            if (settings.autoPlaceSigns()) {
                placeSignsFor(road, plan, ground);
            }
            storage.saveRoad(road);
            onDone.run();
        });
    }

    /** For every wall already standing, cuts the opening this road creates through it. */
    private void cutThroughStandingWalls(RoadPath road, Ground ground) {
        for (Wall wall : registry.wallsIn(road.world())) {
            List<Gate> found = gates.detect(wall, road, settings.gateHeight());
            for (Gate gate : found) {
                wall.putGate(gate);
                if (wall.isBuilt()) {
                    BatchBuilder cut = new BatchBuilder(ground, wallBuild.cutPlacements(wall,
                            new LinkedHashSet<>(gate.openingColumns()), gate.height()));
                    paced(wall.world(), cut, () -> { });
                }
            }
            if (!found.isEmpty()) {
                storage.saveWall(wall);
            }
        }
    }

    public void teardownRoad(RoadPath road, Runnable onDone) {
        Ground ground = BukkitGround.of(road.world());
        if (ground == null) {
            onDone.run();
            return;
        }
        for (Wall wall : registry.wallsIn(road.world())) {
            List<Gate> mine = wall.gates().stream().filter(gate -> gate.roadId().equals(road.id())).toList();
            for (Gate gate : mine) {
                if (wall.isBuilt() && !gate.sealed()) {
                    BatchBuilder seal = new BatchBuilder(ground, occupancy.filter(
                            wallBuild.sealPlacements(wall, new LinkedHashSet<>(gate.openingColumns())),
                            wall.id()));
                    paced(wall.world(), seal, () -> { });
                }
            }
            if (!mine.isEmpty()) {
                wall.removeGatesForRoad(road.id());
                storage.saveWall(wall);
            }
        }
        removeSigns(road);

        BatchBuilder teardown = new BatchBuilder(ground, road.snapshot().asRestorePlacements());
        paced(road.world(), teardown, () -> {
            road.markTornDown();
            occupancy.release(road.id());
            storage.saveRoad(road);
            onDone.run();
        });
    }

    public void deleteRoad(RoadPath road, Runnable onDone) {
        Runnable finish = () -> {
            registry.removeRoad(road.id());
            occupancy.release(road.id());
            storage.deleteRoad(road.id());
            onDone.run();
        };
        if (road.isBuilt()) {
            teardownRoad(road, finish);
        } else {
            finish.run();
        }
    }

    public void removeSigns(RoadPath road) {
        for (RoadSign sign : road.signs()) {
            signs.removeFromWorld(sign);
        }
        road.clearSigns();
        storage.saveRoad(road);
    }

    public void renameSign(RoadPath road, String signId, List<String> newLines) {
        for (RoadSign sign : road.signs()) {
            if (sign.id().equals(signId)) {
                RoadSign renamed = sign.withLines(newLines);
                road.putSign(renamed);
                signs.applyToWorld(renamed);
                storage.saveRoad(road);
                return;
            }
        }
    }

    /** The opposite of {@link #removeSigns}: places this road's default and junction signs again. */
    public void placeSigns(RoadPath road) {
        Ground ground = BukkitGround.of(road.world());
        if (ground == null) {
            return;
        }
        placeSignsFor(road, profiler.profile(road, ground, routeRules()), ground);
        storage.saveRoad(road);
    }

    private void placeSignsFor(RoadPath road, List<RoadSegment> plan, Ground ground) {
        List<Gate> gatesOnThisRoad = new ArrayList<>();
        for (Wall wall : registry.wallsIn(road.world())) {
            gatesOnThisRoad.addAll(wall.gates().stream()
                    .filter(gate -> gate.roadId().equals(road.id())).toList());
        }
        List<RoadPath> built = registry.roadsIn(road.world()).stream().filter(RoadPath::isBuilt).toList();

        List<RoadSign> wanted = new ArrayList<>(signs.defaultSigns(road, plan, gatesOnThisRoad, ground));
        wanted.addAll(signs.junctionSigns(road, plan, built, ground));
        for (RoadSign sign : wanted) {
            road.putSign(sign);
            signs.applyToWorld(sign);
        }
    }

    public void renameWall(Wall wall, String newName) {
        wall.name(newName);
        storage.saveWall(wall);
    }

    public void renameRoad(RoadPath road, String newName) {
        road.name(newName);
        storage.saveRoad(road);
    }

    /**
     * Runs a queue a batch per tick.
     *
     * <p>On the region that owns the world's spawn rather than on a global thread: on Folia a build
     * touching blocks belongs to the region owning them, and the spawn is the one anchor every world
     * is guaranteed to have.
     */
    private void paced(String world, BatchBuilder builder, Runnable onDone) {
        int perBatch = settings.blocksPerBatchClamped();
        if (builder.total() == 0) {
            onDone.run();
            return;
        }
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            onDone.run();
            return;
        }
        Location anchor = bukkitWorld.getSpawnLocation();
        Scheduling.regionTimer(plugin, anchor, 1L, 1L, task -> {
            builder.advance(perBatch);
            if (builder.isDone()) {
                task.cancel();
                onDone.run();
            }
        });
    }
}
