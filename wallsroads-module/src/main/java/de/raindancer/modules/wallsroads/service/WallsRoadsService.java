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
import de.raindancer.modules.wallsroads.model.RoadSign;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.store.WallsRoadsRegistry;
import de.raindancer.modules.wallsroads.store.WallsRoadsStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What actually happens when a wall or road is built, torn down, reshaped or removed — the
 * orchestration the plan's "every action has an opposite" table describes, wired to the world
 * through {@link WallBuildService}/{@link RoadBuildService}/{@link GateService}/{@link SignService}
 * and paced through Core's own {@link Scheduling}.
 */
public final class WallsRoadsService {

    private final Plugin plugin;
    private final LogChannel log;
    private final WallsRoadsRegistry registry;
    private final WallsRoadsStorage storage;
    private final WallBuildService wallBuild = new WallBuildService();
    private final RoadBuildService roadBuild = new RoadBuildService();
    private final GateService gates = new GateService();
    private final SignService signs = new SignService();
    private volatile WallsRoadsSettings settings;

    public WallsRoadsService(Plugin plugin, LogChannel log, WallsRoadsRegistry registry,
                             WallsRoadsStorage storage, WallsRoadsSettings settings) {
        this.plugin = plugin;
        this.log = log;
        this.registry = registry;
        this.storage = storage;
        this.settings = settings;
    }

    public void settings(WallsRoadsSettings updated) {
        this.settings = updated;
    }

    public WallsRoadsRegistry registry() {
        return registry;
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
        BatchBuilder builder = wallBuild.newBuild(ground, wall, openings);
        paced(wall.world(), builder, () -> {
            wall.markBuilt(builder.snapshotSoFar());
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
            storage.saveWall(wall);
            onDone.run();
        });
    }

    public void deleteWall(Wall wall, Runnable onDone) {
        Runnable finish = () -> {
            registry.removeWall(wall.id());
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
        Runnable rebuild = wasBuilt ? () -> buildWall(wall, onDone) : onDone;
        if (wasBuilt) {
            teardownWall(wall, rebuild);
        } else {
            storage.saveWall(wall);
            rebuild.run();
        }
    }

    public void sealGate(Wall wall, String gateId, Runnable onDone) {
        wall.gate(gateId).ifPresentOrElse(gate -> {
            Ground ground = BukkitGround.of(wall.world());
            if (ground == null || gate.sealed()) {
                onDone.run();
                return;
            }
            BatchBuilder seal = wallBuild.newSeal(ground, wall, new LinkedHashSet<>(gate.openingColumns()));
            seal.advance(seal.total());
            wall.replaceGate(gate.asSealed(true));
            storage.saveWall(wall);
            onDone.run();
        }, onDone);
    }

    public void reopenGate(Wall wall, String gateId, Runnable onDone) {
        wall.gate(gateId).ifPresentOrElse(gate -> {
            Ground ground = BukkitGround.of(wall.world());
            if (ground == null || !gate.sealed()) {
                onDone.run();
                return;
            }
            BatchBuilder cut = wallBuild.newCut(ground, wall, new LinkedHashSet<>(gate.openingColumns()), gate.height());
            cut.advance(cut.total());
            wall.replaceGate(gate.asSealed(false));
            storage.saveWall(wall);
            onDone.run();
        }, onDone);
    }

    // ------------------------------------------------------------------------------------ roads

    public void buildRoad(RoadPath road, Runnable onDone) {
        Ground ground = BukkitGround.of(road.world());
        if (ground == null) {
            onDone.run();
            return;
        }
        Map<ColumnPolygon.Column, Integer> heights = roadBuild.surfaceHeights(road, ground);
        BatchBuilder builder = new BatchBuilder(ground, roadBuild.buildPlacements(road, heights));
        paced(road.world(), builder, () -> {
            road.markBuilt(builder.snapshotSoFar());
            List<Gate> gatesOnThisRoad = cutThroughStandingWalls(road, ground);
            if (settings.autoPlaceSigns()) {
                for (RoadSign sign : signs.defaultSigns(road, heights, gatesOnThisRoad)) {
                    road.putSign(sign);
                    signs.applyToWorld(sign);
                }
            }
            storage.saveRoad(road);
            onDone.run();
        });
    }

    /** For every wall already standing, cuts the opening this road creates through it. */
    private List<Gate> cutThroughStandingWalls(RoadPath road, Ground ground) {
        List<Gate> found = new ArrayList<>();
        for (Wall wall : registry.wallsIn(road.world())) {
            for (Gate gate : gates.detect(wall, road, settings.gateHeight())) {
                wall.putGate(gate);
                found.add(gate);
                if (wall.isBuilt()) {
                    BatchBuilder cut = wallBuild.newCut(ground, wall, new LinkedHashSet<>(gate.openingColumns()),
                            gate.height());
                    cut.advance(cut.total());
                }
            }
            storage.saveWall(wall);
        }
        return found;
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
                    BatchBuilder seal = wallBuild.newSeal(ground, wall, new LinkedHashSet<>(gate.openingColumns()));
                    seal.advance(seal.total());
                }
            }
            wall.removeGatesForRoad(road.id());
            storage.saveWall(wall);
        }
        removeSigns(road);

        BatchBuilder teardown = roadBuild.newTeardown(ground, road);
        paced(road.world(), teardown, () -> {
            road.markTornDown();
            storage.saveRoad(road);
            onDone.run();
        });
    }

    public void deleteRoad(RoadPath road, Runnable onDone) {
        Runnable finish = () -> {
            registry.removeRoad(road.id());
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

    /** The opposite of {@link #removeSigns}: places this road's default signs again. */
    public void placeSigns(RoadPath road) {
        Ground ground = BukkitGround.of(road.world());
        if (ground == null) {
            return;
        }
        Map<ColumnPolygon.Column, Integer> heights = roadBuild.surfaceHeights(road, ground);
        List<Gate> gatesOnThisRoad = new ArrayList<>();
        for (Wall wall : registry.wallsIn(road.world())) {
            gatesOnThisRoad.addAll(wall.gates().stream()
                    .filter(gate -> gate.roadId().equals(road.id())).toList());
        }
        for (RoadSign sign : signs.defaultSigns(road, heights, gatesOnThisRoad)) {
            road.putSign(sign);
            signs.applyToWorld(sign);
        }
        storage.saveRoad(road);
    }

    public void renameWall(Wall wall, String newName) {
        wall.name(newName);
        storage.saveWall(wall);
    }

    public void renameRoad(RoadPath road, String newName) {
        road.name(newName);
        storage.saveRoad(road);
    }

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
