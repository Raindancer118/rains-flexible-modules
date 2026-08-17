package de.raindancer.modules.wallsroads.store;

import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Every wall and road this module knows about, in memory. */
public final class WallsRoadsRegistry {

    private final Map<String, Wall> walls = new LinkedHashMap<>();
    private final Map<String, RoadPath> roads = new LinkedHashMap<>();

    public void putWall(Wall wall) {
        walls.put(wall.id(), wall);
    }

    public void putRoad(RoadPath road) {
        roads.put(road.id(), road);
    }

    public Optional<Wall> wall(String id) {
        return Optional.ofNullable(walls.get(id));
    }

    public Optional<RoadPath> road(String id) {
        return Optional.ofNullable(roads.get(id));
    }

    public void removeWall(String id) {
        walls.remove(id);
    }

    public void removeRoad(String id) {
        roads.remove(id);
    }

    public List<Wall> allWalls() {
        return new ArrayList<>(walls.values());
    }

    public List<RoadPath> allRoads() {
        return new ArrayList<>(roads.values());
    }

    public List<Wall> wallsOwnedBy(UUID player) {
        return walls.values().stream().filter(wall -> player.equals(wall.owner())).toList();
    }

    public List<RoadPath> roadsOwnedBy(UUID player) {
        return roads.values().stream().filter(road -> player.equals(road.owner())).toList();
    }

    /** Every wall in the given world whose outline could plausibly touch a road's footprint. */
    public List<Wall> wallsIn(String world) {
        return walls.values().stream().filter(wall -> wall.world().equals(world)).toList();
    }

    public List<RoadPath> roadsIn(String world) {
        return roads.values().stream().filter(road -> road.world().equals(world)).toList();
    }

    public int wallCount() {
        return walls.size();
    }

    public int roadCount() {
        return roads.size();
    }
}
