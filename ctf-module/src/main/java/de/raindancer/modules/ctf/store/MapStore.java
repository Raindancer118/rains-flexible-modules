package de.raindancer.modules.ctf.store;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.modules.ctf.model.Base;
import de.raindancer.modules.ctf.model.CtfMap;
import de.raindancer.modules.ctf.model.Point;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The maps folder: each map a {@code .schem} and beside it a {@code <id>.yml} saying where everything is on it. Spots
 * are one line each ({@code x y z yaw pitch}, relative to the schematic's lowest corner), so a file is easy to fix by
 * hand and a map moves to another server by copying two files.
 *
 * <p>Plain files on purpose, not {@code YamlStore}: that keeps a document under the plugins folder in Core's database
 * and renames the file away, and a map is exactly the thing somebody copies and edits by hand. The maps are read once
 * into memory ({@link #refresh}) — a round, a menu and the lobby board ask every second — and each save updates the
 * memory at once and hands the writing to {@code writer}, so the caller decides which thread touches the disk.
 */
public final class MapStore {

    private static final LogChannel log = Log.of("ctf");

    private final Path folder;
    private final java.util.function.Consumer<Runnable> writer;
    private final java.util.Map<String, CtfMap> cache = new java.util.concurrent.ConcurrentSkipListMap<>();
    private volatile List<String> schematics = List.of();

    /** Reads and writes on the caller's thread — for tests. */
    public MapStore(Path folder) {
        this(folder, Runnable::run);
        refresh();
    }

    public MapStore(Path folder, java.util.function.Consumer<Runnable> writer) {
        this.folder = folder;
        this.writer = writer;
    }

    /** Reads every map file and lists the schematics again. Off the server thread. */
    public void refresh() {
        java.util.Map<String, CtfMap> fresh = new java.util.HashMap<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yml")).sorted().toList()) {
                String id = file.getFileName().toString().replaceFirst("\\.yml$", "");
                readFile(id).ifPresent(map -> fresh.put(id, map));
            }
        } catch (IOException noFolder) {
            // An empty folder, or none yet: no maps.
        }
        cache.keySet().retainAll(fresh.keySet());
        cache.putAll(fresh);
        try (Stream<Path> files = Files.list(folder)) {
            schematics = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".schem")).sorted().toList();
        } catch (IOException noFolder) {
            schematics = List.of();
        }
    }

    public Path folder() {
        return folder;
    }

    /** The schematic files there, by file name, as of the last {@link #refresh}. */
    public List<String> schematics() {
        return schematics;
    }

    /** Every map with a readable file, as of the last {@link #refresh}. */
    public List<CtfMap> all() {
        return new ArrayList<>(cache.values());
    }

    public Optional<CtfMap> load(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(cache.get(id));
    }

    private Optional<CtfMap> readFile(String id) {
        Path file = fileOf(id);
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException unreadable) {
            log.warn("The map file {} cannot be read and is left out: {}", file.getFileName(), unreadable.getMessage());
            return Optional.empty();
        }
        return Optional.ofNullable(read(id, yaml));
    }

    /** Keeps the map at once; the file is written by the writer. */
    public boolean save(CtfMap map) {
        Path file = fileOf(map.id());
        if (file == null) {
            return false;
        }
        cache.put(map.id(), map);
        YamlConfiguration yaml = new YamlConfiguration();
        write(map, yaml);
        String text = "# Where everything is on the map " + map.id() + ". Spots: x y z yaw pitch, from the schematic's "
                + "lowest corner.\n" + yaml.saveToString();
        writer.accept(() -> {
            Path next = file.resolveSibling(file.getFileName() + ".next");
            try {
                Files.writeString(next, text);
                Files.move(next, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                log.error("The map file {} could not be written: {}", file.getFileName(), failure.getMessage());
            }
        });
        return true;
    }

    /** The map's file, or null for an id that would lead out of the folder. */
    Path fileOf(String id) {
        if (!isId(id)) {
            return null;
        }
        return folder.resolve(id + ".yml");
    }

    /** A map id is lower case letters, digits and dashes — it is a file name and a word typed in chat. */
    public static boolean isId(String id) {
        return id != null && id.matches("[a-z0-9][a-z0-9-]{0,31}");
    }

    /** An id made from a schematic's file name. */
    public static String idOf(String schematicFile) {
        String base = schematicFile.replaceFirst("\\.schem$", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "map";
        }
        return base.length() > 32 ? base.substring(0, 32) : base;
    }

    static CtfMap read(String id, YamlConfiguration yaml) {
        String file = yaml.getString("file");
        if (file == null || file.isBlank()) {
            log.warn("The map {} names no schematic file and is left out.", id);
            return null;
        }
        int[] size = ints(yaml.getString("size", "0 0 0"));
        List<Base> bases = new ArrayList<>();
        ConfigurationSection basesSection = yaml.getConfigurationSection("bases");
        if (basesSection != null) {
            for (String key : basesSection.getKeys(false)) {
                Optional<TeamColour> colour = colour(key);
                if (colour.isEmpty()) {
                    log.warn("The map {} has a base of an unknown colour, {}; it is left out.", id, key);
                    continue;
                }
                ConfigurationSection base = basesSection.getConfigurationSection(key);
                Point flag = base == null ? null : Point.parse(base.getString("flag")).orElse(null);
                List<Point> spawns = base == null ? List.of() : points(base.getStringList("spawns"));
                bases.add(new Base(colour.get(), flag, spawns));
            }
        }
        return new CtfMap(id, file, yaml.getString("title", id), yaml.getString("author", ""), size[0], size[1],
                size[2], bases, Point.parse(yaml.getString("neutral")).orElse(null),
                Point.parse(yaml.getString("control")).orElse(null), points(yaml.getStringList("power-ups")),
                points(yaml.getStringList("supplies")), yaml.getInt("floor", -1),
                Point.parse(yaml.getString("spectate")).orElse(null));
    }

    static void write(CtfMap map, YamlConfiguration yaml) {
        yaml.set("file", map.file());
        yaml.set("title", map.title());
        yaml.set("author", map.author());
        yaml.set("size", map.width() + " " + map.height() + " " + map.length());
        yaml.set("floor", map.floor());
        if (map.neutral() != null) {
            yaml.set("neutral", map.neutral().line());
        }
        if (map.control() != null) {
            yaml.set("control", map.control().line());
        }
        if (map.spectate() != null) {
            yaml.set("spectate", map.spectate().line());
        }
        yaml.set("power-ups", map.powerUps().stream().map(Point::line).toList());
        yaml.set("supplies", map.supplies().stream().map(Point::line).toList());
        for (Base base : map.bases()) {
            String at = "bases." + base.colour().name().toLowerCase(Locale.ROOT);
            if (base.flag() != null) {
                yaml.set(at + ".flag", base.flag().line());
            }
            yaml.set(at + ".spawns", base.spawns().stream().map(Point::line).toList());
        }
    }

    private static List<Point> points(List<String> lines) {
        return lines.stream().map(Point::parse).flatMap(Optional::stream).toList();
    }

    private static int[] ints(String line) {
        int[] out = new int[3];
        String[] parts = line.trim().split("\\s+");
        for (int index = 0; index < Math.min(3, parts.length); index++) {
            try {
                out[index] = Integer.parseInt(parts[index]);
            } catch (NumberFormatException garbled) {
                out[index] = 0;
            }
        }
        return out;
    }

    public static Optional<TeamColour> colour(String name) {
        try {
            return Optional.of(TeamColour.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return Optional.empty();
        }
    }
}
