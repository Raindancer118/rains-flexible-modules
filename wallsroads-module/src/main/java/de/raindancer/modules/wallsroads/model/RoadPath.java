package de.raindancer.modules.wallsroads.model;

import de.raindancer.core.world.build.BuildSnapshot;
import de.raindancer.core.world.geometry.Polyline;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A road: an open {@link Polyline}, a width, a material, and how its surface height is decided. */
public final class RoadPath {

    private final String id;
    private String name;
    private final UUID owner;
    private final String world;
    private Polyline path;
    private double width;
    private Material material;
    private ElevationMode elevationMode;
    private int fixedY;
    private boolean built;
    private BuildSnapshot snapshot = BuildSnapshot.empty();
    private final Map<String, RoadSign> signs = new LinkedHashMap<>();

    public RoadPath(String id, String name, UUID owner, String world, Polyline path, double width,
                    Material material, ElevationMode elevationMode, int fixedY) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.world = world;
        this.path = path;
        this.width = Math.max(1, width);
        this.material = material;
        this.elevationMode = elevationMode == null ? ElevationMode.FIXED_Y : elevationMode;
        this.fixedY = fixedY;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void name(String value) {
        this.name = value;
    }

    public UUID owner() {
        return owner;
    }

    public String world() {
        return world;
    }

    public Polyline path() {
        return path;
    }

    public void path(Polyline value) {
        this.path = value;
    }

    public double width() {
        return width;
    }

    public void width(double value) {
        this.width = Math.max(1, value);
    }

    public Material material() {
        return material;
    }

    public void material(Material value) {
        this.material = value;
    }

    public ElevationMode elevationMode() {
        return elevationMode;
    }

    public void elevationMode(ElevationMode value) {
        this.elevationMode = value == null ? ElevationMode.FIXED_Y : value;
    }

    public int fixedY() {
        return fixedY;
    }

    public void fixedY(int value) {
        this.fixedY = value;
    }

    public boolean isBuilt() {
        return built;
    }

    public void markBuilt(BuildSnapshot newSnapshot) {
        this.built = true;
        this.snapshot = newSnapshot;
    }

    public void markTornDown() {
        this.built = false;
        this.snapshot = BuildSnapshot.empty();
    }

    public BuildSnapshot snapshot() {
        return snapshot;
    }

    public List<RoadSign> signs() {
        return new ArrayList<>(signs.values());
    }

    public void putSign(RoadSign sign) {
        signs.put(sign.id(), sign);
    }

    public void removeSign(String signId) {
        signs.remove(signId);
    }

    public void clearSigns() {
        signs.clear();
    }
}
