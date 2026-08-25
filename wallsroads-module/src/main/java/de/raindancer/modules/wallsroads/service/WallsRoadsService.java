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
    private final MaterialBill bill = new MaterialBill();
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
        buildWall(wall, null, onDone);
    }

    /**
     * Builds a wall, charging the blocks to {@code payer} when the server asks for that.
     *
     * @param payer whoever is paying, or {@code null} for a build nobody is standing behind — a
     *              rebuild after a reshape, or a server that does not charge at all
     */
    public void buildWall(Wall wall, org.bukkit.entity.Player payer, Runnable onDone) {
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
        // Nothing passed: a gate cuts its own arch out of the finished wall now, and handing its
        // columns over as well would clear them full height and put the ragged rectangular hole back.
        List<BatchBuilder.Placement> wanted =
                occupancy.filter(wallBuild.buildPlacements(wall, Set.of(), ground), wall.id());
        List<BatchBuilder.Placement> affordable = charge(payer, wanted);
        BatchBuilder builder = new BatchBuilder(ground, affordable);
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

    /** Closes a gate's doors. Paced like everything else — a wide gate is a lot of blocks. */
    public void shutGate(Wall wall, String gateId, Runnable onDone) {
        wall.gate(gateId).ifPresentOrElse(gate -> {
            Ground ground = BukkitGround.of(wall.world());
            if (ground == null || gate.shut() || gate.sealed()) {
                onDone.run();
                return;
            }
            BatchBuilder shut = new BatchBuilder(ground, gates.shutPlacements(wall, gate));
            paced(wall.world(), shut, () -> {
                wall.replaceGate(gate.asShut(true));
                storage.saveWall(wall);
                onDone.run();
            });
        }, onDone);
    }

    public void openGate(Wall wall, String gateId, Runnable onDone) {
        wall.gate(gateId).ifPresentOrElse(gate -> {
            Ground ground = BukkitGround.of(wall.world());
            if (ground == null || !gate.shut()) {
                onDone.run();
                return;
            }
            BatchBuilder open = new BatchBuilder(ground, gates.openPlacements(wall, gate));
            paced(wall.world(), open, () -> {
                wall.replaceGate(gate.asShut(false));
                storage.saveWall(wall);
                onDone.run();
            });
        }, onDone);
    }

    /**
     * Shuts every gate on every wall set to close at night, and opens them again at dawn.
     *
     * <p>Only gates that are neither sealed nor already in the state being asked for are touched, so
     * a gate somebody deliberately opened at midnight is not slammed shut a tick later — it is left
     * until the next change of day.
     */
    public void applyCurfew(boolean night) {
        for (Wall wall : registry.allWalls()) {
            if (!wall.isBuilt() || !wall.closesAtNight()) {
                continue;
            }
            for (Gate gate : wall.gates()) {
                if (gate.sealed() || gate.shut() == night) {
                    continue;
                }
                if (night) {
                    shutGate(wall, gate.id(), () -> { });
                } else {
                    openGate(wall, gate.id(), () -> { });
                }
            }
        }
    }

    public GateService gates() {
        return gates;
    }

    // ------------------------------------------------------------------------------------ roads

    public void buildRoad(RoadPath road, Runnable onDone) {
        buildRoad(road, null, onDone);
    }

    public void buildRoad(RoadPath road, org.bukkit.entity.Player payer, Runnable onDone) {
        Ground ground = BukkitGround.of(road.world());
        if (ground == null) {
            onDone.run();
            return;
        }
        List<RoadSegment> plan = profiler.profile(road, ground, routeRules());
        List<BatchBuilder.Placement> wanted =
                occupancy.filter(roadBuilder.placements(road, plan, ground), road.id());
        BatchBuilder builder = new BatchBuilder(ground, charge(payer, wanted));
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
     * Takes the blocks out of the builder's inventory, and hands back the part of the queue those
     * blocks stretch to.
     *
     * <p>Charged up front rather than block by block: a build runs over many ticks, and somebody who
     * walked away halfway through would otherwise have paid for a road that stopped where they did.
     */
    private List<BatchBuilder.Placement> charge(org.bukkit.entity.Player payer,
                                                List<BatchBuilder.Placement> wanted) {
        if (payer == null || !settings.chargeMaterials()
                || payer.hasPermission(de.raindancer.modules.wallsroads.util.PermissionNodes.BUILD_FREE)) {
            return wanted;
        }
        List<BatchBuilder.Placement> affordable = bill.affordable(wanted, bill.carriedBy(payer));
        bill.charge(payer, bill.costOf(affordable));
        if (affordable.size() < wanted.size()) {
            log.info("{} could pay for {} of {} blocks.", payer.getName(), affordable.size(), wanted.size());
        }
        return affordable;
    }

    /** What this wall would cost to build right now, by material. */
    public java.util.Map<String, Integer> estimateWall(Wall wall) {
        Ground ground = BukkitGround.of(wall.world());
        return bill.costOf(wallBuild.buildPlacements(wall, wallBuild.openGateColumns(wall), ground));
    }

    /** And this road. */
    public java.util.Map<String, Integer> estimateRoad(RoadPath road) {
        Ground ground = BukkitGround.of(road.world());
        if (ground == null) {
            return java.util.Map.of();
        }
        return bill.costOf(roadBuilder.placements(road, profiler.profile(road, ground, routeRules()), ground));
    }

    public MaterialBill bill() {
        return bill;
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
