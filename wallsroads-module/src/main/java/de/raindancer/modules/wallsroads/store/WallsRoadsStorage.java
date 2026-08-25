package de.raindancer.modules.wallsroads.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.world.build.BuildSnapshot;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.geometry.Polyline;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.CornerStyle;
import de.raindancer.modules.wallsroads.model.ElevationMode;
import de.raindancer.modules.wallsroads.model.Gate;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.RoadProfile;
import de.raindancer.modules.wallsroads.model.RoadSign;
import de.raindancer.modules.wallsroads.model.Wall;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One YAML file per wall and per road, read and written through Core's {@link YamlStore} — the
 * same write-temp-then-move guarantees every other store in this repository already relies on,
 * rather than a bespoke copy of that dance.
 */
public final class WallsRoadsStorage {

    private final Path wallsDir;
    private final Path roadsDir;

    public WallsRoadsStorage(Path dataFolder) {
        this.wallsDir = dataFolder.resolve("walls");
        this.roadsDir = dataFolder.resolve("roads");
    }

    public void ensureDirectories() throws IOException {
        Files.createDirectories(wallsDir);
        Files.createDirectories(roadsDir);
    }

    // ------------------------------------------------------------------------------------ walls

    public List<Wall> loadAllWalls() {
        return loadAll(wallsDir, this::readWall);
    }

    public boolean saveWall(Wall wall) {
        return new YamlStore(wallsDir.resolve(wall.id() + ".yml")).write(yaml -> writeWall(yaml, wall));
    }

    public void deleteWall(String id) {
        delete(wallsDir, id);
    }

    private void writeWall(YamlConfiguration yaml, Wall wall) {
        yaml.set("id", wall.id());
        yaml.set("name", wall.name());
        yaml.set("owner", wall.owner() == null ? null : wall.owner().toString());
        yaml.set("world", wall.world());
        yaml.set("vertices", serializeColumns(wall.outline().vertices()));
        yaml.set("min-y", wall.minY());
        yaml.set("height", wall.height());
        yaml.set("material", wall.material().name());
        yaml.set("thickness", wall.thickness());
        yaml.set("corner-radius", wall.cornerStyle().radius());
        yaml.set("built", wall.isBuilt());
        yaml.set("snapshot", serializeSnapshot(wall.snapshot()));

        ConfigurationSection gatesSection = yaml.createSection("gates");
        for (Gate gate : wall.gates()) {
            ConfigurationSection gateSection = gatesSection.createSection(gate.id());
            gateSection.set("road-id", gate.roadId());
            gateSection.set("columns", serializeColumns(gate.openingColumns()));
            gateSection.set("height", gate.height());
            gateSection.set("sealed", gate.sealed());
        }
    }

    private Wall readWall(YamlConfiguration yaml) {
        String id = yaml.getString("id");
        String name = yaml.getString("name", id);
        String ownerRaw = yaml.getString("owner");
        UUID owner = ownerRaw == null ? null : UUID.fromString(ownerRaw);
        String world = yaml.getString("world");
        ColumnPolygon outline = new ColumnPolygon(deserializeColumns(yaml.getStringList("vertices")));
        int minY = yaml.getInt("min-y");
        int height = yaml.getInt("height", 6);
        Material material = Material.matchMaterial(yaml.getString("material", "STONE_BRICKS"));
        int thickness = yaml.getInt("thickness", 1);
        int radius = yaml.getInt("corner-radius", 0);

        Wall wall = new Wall(id, name, owner, world, outline, minY, height,
                material == null ? Material.STONE_BRICKS : material, thickness,
                radius > 0 ? CornerStyle.rounded(radius) : CornerStyle.SHARP);

        if (yaml.getBoolean("built", false)) {
            wall.markBuilt(deserializeSnapshot(yaml.getStringList("snapshot")));
        }

        ConfigurationSection gatesSection = yaml.getConfigurationSection("gates");
        if (gatesSection != null) {
            for (String gateId : gatesSection.getKeys(false)) {
                ConfigurationSection gateSection = gatesSection.getConfigurationSection(gateId);
                if (gateSection == null) {
                    continue;
                }
                wall.putGate(new Gate(gateId, id, gateSection.getString("road-id", ""),
                        deserializeColumns(gateSection.getStringList("columns")),
                        gateSection.getInt("height", 4), gateSection.getBoolean("sealed", false)));
            }
        }
        return wall;
    }

    // ------------------------------------------------------------------------------------ roads

    public List<RoadPath> loadAllRoads() {
        return loadAll(roadsDir, this::readRoad);
    }

    public boolean saveRoad(RoadPath road) {
        return new YamlStore(roadsDir.resolve(road.id() + ".yml")).write(yaml -> writeRoad(yaml, road));
    }

    public void deleteRoad(String id) {
        delete(roadsDir, id);
    }

    private void writeRoad(YamlConfiguration yaml, RoadPath road) {
        yaml.set("id", road.id());
        yaml.set("name", road.name());
        yaml.set("owner", road.owner() == null ? null : road.owner().toString());
        yaml.set("world", road.world());
        yaml.set("points", serializeColumns(road.path().points()));
        yaml.set("width", road.width());
        yaml.set("material", road.material().name());
        yaml.set("elevation-mode", road.elevationMode().name());
        writeProfile(yaml.createSection("profile"), road.profile());
        yaml.set("fixed-y", road.fixedY());
        yaml.set("built", road.isBuilt());
        yaml.set("snapshot", serializeSnapshot(road.snapshot()));

        ConfigurationSection signsSection = yaml.createSection("signs");
        for (RoadSign sign : road.signs()) {
            ConfigurationSection signSection = signsSection.createSection(sign.id());
            signSection.set("spot", spotToString(sign.spot()));
            signSection.set("rotation", sign.rotation());
            signSection.set("lines", sign.lines());
            signSection.set("replaced", sign.replaced());
        }
    }

    private RoadPath readRoad(YamlConfiguration yaml) {
        String id = yaml.getString("id");
        String name = yaml.getString("name", id);
        String ownerRaw = yaml.getString("owner");
        UUID owner = ownerRaw == null ? null : UUID.fromString(ownerRaw);
        String world = yaml.getString("world");
        Polyline path = new Polyline(deserializeColumns(yaml.getStringList("points")));
        double width = yaml.getDouble("width", 5);
        Material material = Material.matchMaterial(yaml.getString("material", "GRAVEL"));
        ElevationMode mode = ElevationMode.valueOf(yaml.getString("elevation-mode", "FIXED_Y"));
        int fixedY = yaml.getInt("fixed-y", 64);

        RoadPath road = new RoadPath(id, name, owner, world, path, width,
                material == null ? Material.GRAVEL : material, mode, fixedY,
                readProfile(yaml.getConfigurationSection("profile")));

        if (yaml.getBoolean("built", false)) {
            road.markBuilt(deserializeSnapshot(yaml.getStringList("snapshot")));
        }

        ConfigurationSection signsSection = yaml.getConfigurationSection("signs");
        if (signsSection != null) {
            for (String signId : signsSection.getKeys(false)) {
                ConfigurationSection signSection = signsSection.getConfigurationSection(signId);
                if (signSection == null) {
                    continue;
                }
                road.putSign(new RoadSign(signId, id, stringToSpot(signSection.getString("spot", "")),
                        signSection.getInt("rotation", 0), signSection.getStringList("lines"),
                        signSection.getString("replaced", "AIR")));
            }
        }
        return road;
    }

    // ---------------------------------------------------------------------------------- profiles

    private static void writeProfile(ConfigurationSection section, RoadProfile profile) {
        section.set("kerb", nameOf(profile.kerb()));
        section.set("lamp", nameOf(profile.lamp()));
        section.set("lamp-post", nameOf(profile.lampPost()));
        section.set("lamp-spacing", profile.lampSpacing());
        section.set("railing", nameOf(profile.railing()));
        section.set("support", nameOf(profile.support()));
        section.set("tunnel-lining", nameOf(profile.tunnelLining()));
        section.set("tunnel-light", nameOf(profile.tunnelLight()));
        section.set("glass", nameOf(profile.glass()));
        section.set("headroom", profile.headroom());
    }

    /**
     * A missing section is the plain profile — that is what every road written before profiles
     * existed had, and reading one as "no materials at all" would build a road with no railings on
     * its bridges.
     */
    private static RoadProfile readProfile(ConfigurationSection section) {
        RoadProfile plain = RoadProfile.plain();
        if (section == null) {
            return plain;
        }
        return new RoadProfile(
                material(section.getString("kerb"), plain.kerb()),
                material(section.getString("lamp"), plain.lamp()),
                material(section.getString("lamp-post"), plain.lampPost()),
                section.getInt("lamp-spacing", plain.lampSpacing()),
                material(section.getString("railing"), plain.railing()),
                material(section.getString("support"), plain.support()),
                material(section.getString("tunnel-lining"), plain.tunnelLining()),
                material(section.getString("tunnel-light"), plain.tunnelLight()),
                material(section.getString("glass"), plain.glass()),
                section.getInt("headroom", plain.headroom()));
    }

    private static String nameOf(Material material) {
        return material == null ? "" : material.name();
    }

    /** An unknown or blank material falls back rather than becoming null halfway through a build. */
    private static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Material found = Material.matchMaterial(name);
        return found == null ? fallback : found;
    }

    // ------------------------------------------------------------------------------------ shared

    private interface Reader<T> {
        T read(YamlConfiguration yaml);
    }

    private <T> List<T> loadAll(Path directory, Reader<T> reader) {
        List<T> loaded = new ArrayList<>();
        File[] files = directory.toFile().listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return loaded;
        }
        for (File file : files) {
            YamlStore store = new YamlStore(file.toPath());
            YamlConfiguration yaml = store.read();
            if (yaml.getString("id") == null) {
                continue;
            }
            loaded.add(reader.read(yaml));
        }
        return loaded;
    }

    private void delete(Path directory, String id) {
        try {
            Files.deleteIfExists(directory.resolve(id + ".yml"));
        } catch (IOException ignored) {
            // Nothing left to remove is not a failure worth reporting.
        }
    }

    private static List<String> serializeColumns(List<ColumnPolygon.Column> columns) {
        List<String> serialized = new ArrayList<>(columns.size());
        for (ColumnPolygon.Column column : columns) {
            serialized.add(column.serialize());
        }
        return serialized;
    }

    /**
     * A damaged line is dropped rather than defaulted. A column that will not parse read as 0/0
     * would put a corner of somebody's wall at the world origin, and the wall would only be visibly
     * wrong once it had been built across the map.
     */
    private static List<ColumnPolygon.Column> deserializeColumns(List<String> raw) {
        List<ColumnPolygon.Column> columns = new ArrayList<>(raw.size());
        for (String entry : raw) {
            ColumnPolygon.Column.deserialize(entry).ifPresent(columns::add);
        }
        return columns;
    }

    private static List<String> serializeSnapshot(BuildSnapshot snapshot) {
        List<String> serialized = new ArrayList<>(snapshot.size());
        for (BuildSnapshot.Placement placement : snapshot.placements()) {
            serialized.add(spotToString(placement.spot()) + "=" + placement.material());
        }
        return serialized;
    }

    private static BuildSnapshot deserializeSnapshot(List<String> raw) {
        List<BuildSnapshot.Placement> placements = new ArrayList<>(raw.size());
        for (String entry : raw) {
            int split = entry.lastIndexOf('=');
            if (split < 0) {
                continue;
            }
            placements.add(new BuildSnapshot.Placement(stringToSpot(entry.substring(0, split)),
                    entry.substring(split + 1)));
        }
        return new BuildSnapshot(placements);
    }

    private static String spotToString(Spot spot) {
        return spot.world() + "," + spot.x() + "," + spot.y() + "," + spot.z();
    }

    private static Spot stringToSpot(String raw) {
        String[] parts = raw.split(",", 4);
        return new Spot(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }
}
