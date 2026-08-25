package de.raindancer.modules.wallsroads.model;

import de.raindancer.core.world.build.BuildSnapshot;
import de.raindancer.core.world.geometry.ColumnPolygon;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A town wall: a closed {@link ColumnPolygon}, a vertical range, a material and thickness, and the
 * gates a road has cut through it. What a wall <em>is</em> — the module's own domain model, the same
 * role {@code claims-module}'s {@code Claim} plays there.
 *
 * <p>Mutable, like {@code Claim}: an owner reshapes, rebuilds and renames the same wall rather than
 * replacing it, and {@link #snapshot()} has to survive exactly that — it is what a teardown restores.
 */
public final class Wall {

    private final String id;
    private String name;
    private final UUID owner;
    private final String world;
    private ColumnPolygon outline;
    private int minY;
    private int height;
    private Material material;
    private int thickness;
    private CornerStyle cornerStyle;
    private WallProfile profile = WallProfile.simple();
    private boolean closesAtNight;
    private boolean gatesOpenToEveryone = true;
    private boolean built;
    private BuildSnapshot snapshot = BuildSnapshot.empty();
    private final Map<String, Gate> gates = new LinkedHashMap<>();

    public Wall(String id, String name, UUID owner, String world, ColumnPolygon outline,
               int minY, int height, Material material, int thickness, CornerStyle cornerStyle) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.world = world;
        this.outline = outline;
        this.minY = minY;
        this.height = Math.max(1, height);
        this.material = material;
        this.thickness = Math.max(1, Math.min(5, thickness));
        this.cornerStyle = cornerStyle == null ? CornerStyle.SHARP : cornerStyle;
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

    public ColumnPolygon outline() {
        return outline;
    }

    public void outline(ColumnPolygon value) {
        this.outline = value;
    }

    public int minY() {
        return minY;
    }

    public int height() {
        return height;
    }

    public void bounds(int newMinY, int newHeight) {
        this.minY = newMinY;
        this.height = Math.max(1, newHeight);
    }

    public Material material() {
        return material;
    }

    public void material(Material value) {
        this.material = value;
    }

    public int thickness() {
        return thickness;
    }

    public void thickness(int value) {
        this.thickness = Math.max(1, Math.min(5, value));
    }

    public CornerStyle cornerStyle() {
        return cornerStyle;
    }

    public void cornerStyle(CornerStyle value) {
        this.cornerStyle = value == null ? CornerStyle.SHARP : value;
    }

    public WallProfile profile() {
        return profile;
    }

    public void profile(WallProfile value) {
        this.profile = value == null ? WallProfile.simple() : value;
    }

    /** Whether this wall's gates shut themselves at nightfall. */
    public boolean closesAtNight() {
        return closesAtNight;
    }

    public void closesAtNight(boolean value) {
        this.closesAtNight = value;
    }

    /**
     * Whether anybody may work this wall's gates.
     *
     * <p>True by default: a town gate people cannot open is a wall, and somebody who marked out a
     * wall around a village has not thereby said the village is closed.
     */
    public boolean gatesOpenToEveryone() {
        return gatesOpenToEveryone;
    }

    public void gatesOpenToEveryone(boolean value) {
        this.gatesOpenToEveryone = value;
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

    /** The polygon this wall actually builds from — corner-rounded when its style asks for that. */
    public ColumnPolygon effectiveOutline() {
        return cornerStyle.isRounded() ? outline.rounded(cornerStyle.radius()) : outline;
    }

    public List<Gate> gates() {
        return new ArrayList<>(gates.values());
    }

    public Optional<Gate> gate(String gateId) {
        return Optional.ofNullable(gates.get(gateId));
    }

    public void putGate(Gate gate) {
        gates.put(gate.id(), gate);
    }

    public void removeGatesForRoad(String roadId) {
        gates.values().removeIf(gate -> gate.roadId().equals(roadId));
    }

    public void replaceGate(Gate gate) {
        gates.put(gate.id(), gate);
    }
}
