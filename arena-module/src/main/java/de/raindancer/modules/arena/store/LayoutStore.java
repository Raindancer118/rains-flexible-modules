package de.raindancer.modules.arena.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.arena.model.ArenaWorld;
import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.model.Layout;
import de.raindancer.modules.arena.model.Pit;
import de.raindancer.modules.arena.model.Place;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;

/**
 * {@code layout.yml}: the arena's worlds, its hub and its pits. Written by the admin commands, readable and fixable by
 * hand, so a box is six numbers on one line rather than a nested section nobody wants to type.
 */
public final class LayoutStore {

    private static final LogChannel log = Log.of("arena");

    private final YamlStore file;

    public LayoutStore(Path file) {
        this.file = new YamlStore(file);
    }

    public boolean save(Layout layout) {
        return file.write(yaml -> {
            for (ArenaWorld world : layout.worlds()) {
                String at = "worlds." + YamlStore.asPathPart(world.name());
                yaml.set(at + ".name", world.name());
                yaml.set(at + ".map", world.map());
                yaml.set(at + ".box", box(world.mapBox()));
            }
            layout.hub().ifPresent(hub -> yaml.set("hub", place(hub)));
            for (Pit pit : layout.pits()) {
                String at = "pits." + YamlStore.asPathPart(pit.id());
                yaml.set(at + ".world", pit.world());
                if (pit.floor() != null) {
                    yaml.set(at + ".floor", box(pit.floor()));
                }
                yaml.set(at + ".spawns", pit.spawns().stream().map(LayoutStore::place).toList());
            }
        });
    }

    public Layout load() {
        YamlConfiguration yaml = file.read();
        if (!file.problems().isEmpty()) {
            log.error("layout.yml could not be read: {}. The arena starts with nothing set up until it is fixed.",
                    file.problems());
        }
        Layout layout = Layout.EMPTY;
        ConfigurationSection worlds = yaml.getConfigurationSection("worlds");
        if (worlds != null) {
            for (String key : worlds.getKeys(false)) {
                try {
                    String name = worlds.getString(key + ".name", YamlStore.fromPathPart(key));
                    layout = layout.withWorld(new ArenaWorld(name, required(worlds.getString(key + ".map")),
                            box(name, worlds.getString(key + ".box"))));
                } catch (IllegalArgumentException broken) {
                    log.error("Arena world '{}' in layout.yml is skipped: {}", key, broken.getMessage());
                }
            }
        }
        String hub = yaml.getString("hub");
        if (hub != null) {
            try {
                layout = layout.withHub(place(hub));
            } catch (IllegalArgumentException broken) {
                log.error("The hub in layout.yml is skipped: {}", broken.getMessage());
            }
        }
        ConfigurationSection pits = yaml.getConfigurationSection("pits");
        if (pits != null) {
            for (String key : pits.getKeys(false)) {
                try {
                    String world = required(pits.getString(key + ".world"));
                    Pit pit = Pit.named(YamlStore.fromPathPart(key), world);
                    String floor = pits.getString(key + ".floor");
                    if (floor != null) {
                        pit = pit.withFloor(box(world, floor));
                    }
                    for (String spawn : pits.getStringList(key + ".spawns")) {
                        pit = pit.withSpawn(place(spawn));
                    }
                    layout = layout.withPit(pit);
                } catch (IllegalArgumentException broken) {
                    log.error("Pit '{}' in layout.yml is skipped: {}", key, broken.getMessage());
                }
            }
        }
        return layout;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a value is missing");
        }
        return value;
    }

    /** {@code "minX minY minZ maxX maxY maxZ"} */
    private static String box(Box box) {
        return box.minX() + " " + box.minY() + " " + box.minZ() + " " + box.maxX() + " " + box.maxY() + " "
                + box.maxZ();
    }

    private static Box box(String world, String line) {
        String[] parts = required(line).trim().split("\\s+");
        if (parts.length != 6) {
            throw new IllegalArgumentException("a box is six numbers, not '" + line + "'");
        }
        int[] numbers = new int[6];
        for (int index = 0; index < 6; index++) {
            numbers[index] = Integer.parseInt(parts[index]);
        }
        return Box.of(world, numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5]);
    }

    /** {@code "world x y z yaw pitch"} — the world first, since a name with spaces would not survive the split. */
    static String place(Place place) {
        return place.world() + " " + place.x() + " " + place.y() + " " + place.z() + " " + place.yaw() + " "
                + place.pitch();
    }

    static Place place(String line) {
        String[] parts = required(line).trim().split("\\s+");
        if (parts.length != 6) {
            throw new IllegalArgumentException("a place is a world and five numbers, not '" + line + "'");
        }
        return new Place(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
    }

}
