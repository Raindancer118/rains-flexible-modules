package de.raindancer.modules.ctf.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.world.build.Schematic;
import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.Base;
import de.raindancer.modules.ctf.model.CtfMap;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.store.MapStore;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Setting a map up: it is built where it will be played, staff walk round it in creative and mark where each base's flag
 * and spawns are, the power-ups, the drops, the middle and the floor — each where they stand. Banners already in a
 * downloaded map are offered as the flags.
 */
public final class MapSetupService implements ICtfService {

    /** Where somebody is setting a map up. */
    public record Editing(String mapId, ArenaWorldService.Built where) {
    }

    private final Plugin plugin;
    private final MapStore maps;
    private final ArenaWorldService arena;
    private final Map<UUID, Editing> editing = new ConcurrentHashMap<>();
    private volatile CtfSettings settings;

    public MapSetupService(Plugin plugin, MapStore maps, ArenaWorldService arena, CtfSettings settings) {
        this.plugin = plugin;
        this.maps = maps;
        this.arena = arena;
        this.settings = settings;
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    public Optional<Editing> editing(UUID admin) {
        return Optional.ofNullable(editing.get(admin));
    }

    /**
     * Starts setting up a map from a schematic file (a new map) or an existing map's id: builds it, takes the admin there
     * in creative.
     *
     * @param said told a message key and its values, on the global thread
     */
    public void begin(Player admin, String fileOrId, Consumer<Object[]> said) {
        Optional<CtfMap> existing = maps.load(fileOrId.toLowerCase(Locale.ROOT));
        CtfMap map;
        if (existing.isPresent()) {
            map = existing.get();
        } else {
            String file = fileOrId.endsWith(".schem") ? fileOrId : fileOrId + ".schem";
            if (!maps.schematics().contains(file)) {
                // Dropped into the folder since the last look: an admin's command can afford a directory listing.
                maps.refresh();
            }
            if (!maps.schematics().contains(file)) {
                said.accept(new Object[]{"ctf.admin.no-such-schematic", "file", file});
                return;
            }
            Optional<Schematic> read = read(file);
            if (read.isEmpty()) {
                said.accept(new Object[]{"ctf.admin.schematic-unreadable"});
                return;
            }
            map = suggestFlags(CtfMap.fresh(MapStore.idOf(file), file, read.get().width(), read.get().height(),
                    read.get().length()).withFloor(0), read.get());
            maps.save(map);
        }
        CtfMap ready = map;
        Consumer<ArenaWorldService.Built> arrive = where -> {
            editing.put(admin.getUniqueId(), new Editing(ready.id(), where));
            Location centre = arena.location(where, Point.of(ready.width() / 2.0, ready.height() + 2,
                    ready.length() / 2.0)).orElse(null);
            if (centre != null) {
                Scheduling.entity(plugin, admin, () -> {
                    admin.setGameMode(GameMode.CREATIVE);
                    admin.teleportAsync(centre);
                });
            }
            said.accept(new Object[]{"ctf.admin.setup-begun", "map", ready.id(), "missing",
                    String.join(", ", ready.missingFor(de.raindancer.modules.ctf.model.GameType.CLASSIC))});
        };
        Optional<ArenaWorldService.Built> built = arena.builtNow(map);
        if (built.isPresent()) {
            arrive.accept(built.get());
            return;
        }
        said.accept(new Object[]{"ctf.admin.building", "map", map.id()});
        arena.build(map, arrive, key -> said.accept(new Object[]{key}));
    }

    private Optional<Schematic> read(String file) {
        try {
            return Optional.of(Schematic.read(maps.folder().resolve(file)));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Banners in the schematic, by colour: the colour with banners becomes a base, its flag where its banners are on
     * average, at the height of the lowest one, its spawn three blocks off towards the middle. Only for colours with
     * banners in one half of the map along its long side — decoration all round tells nothing.
     */
    static CtfMap suggestFlags(CtfMap map, Schematic schematic) {
        Map<TeamColour, List<int[]>> banners = new LinkedHashMap<>();
        for (int layer = 0; layer < schematic.height(); layer++) {
            for (int row = 0; row < schematic.length(); row++) {
                for (int column = 0; column < schematic.width(); column++) {
                    int x = column;
                    int y = layer;
                    int z = row;
                    String state = schematic.stateAt(x, y, z);
                    if (state == null || !state.contains("_banner")) {
                        continue;
                    }
                    String name = state.replace("minecraft:", "").replaceAll("\\[.*", "")
                            .replace("_wall_banner", "").replace("_banner", "");
                    MapStore.colour(name).ifPresent(colour ->
                            banners.computeIfAbsent(colour, any -> new ArrayList<>()).add(new int[]{x, y, z}));
                }
            }
        }
        double middleX = schematic.width() / 2.0;
        double middleZ = schematic.length() / 2.0;
        CtfMap suggested = map;
        for (Map.Entry<TeamColour, List<int[]>> entry : banners.entrySet()) {
            List<int[]> spots = entry.getValue();
            double x = spots.stream().mapToInt(spot -> spot[0]).average().orElse(0);
            double z = spots.stream().mapToInt(spot -> spot[2]).average().orElse(0);
            int y = spots.stream().mapToInt(spot -> spot[1]).min().orElse(0);
            // Bases sit at the two ends of a map's long side; banners in both halves are decoration.
            boolean alongX = schematic.width() >= schematic.length();
            boolean oneSide = alongX
                    ? spots.stream().allMatch(spot -> spot[0] < middleX) || spots.stream().allMatch(spot -> spot[0] > middleX)
                    : spots.stream().allMatch(spot -> spot[2] < middleZ) || spots.stream().allMatch(spot -> spot[2] > middleZ);
            if (!oneSide) {
                continue;
            }
            double towardX = Math.signum(middleX - x) * 3;
            double towardZ = Math.abs(middleX - x) >= Math.abs(middleZ - z) ? 0 : Math.signum(middleZ - z) * 3;
            float yaw = (float) Math.toDegrees(Math.atan2(-(middleX - x), middleZ - z));
            suggested = suggested.withBase(new Base(entry.getKey(), new Point(Math.floor(x), y, Math.floor(z), yaw, 0f),
                    List.of(new Point(Math.floor(x) + 0.5 + towardX, y, Math.floor(z) + 0.5 + towardZ, yaw, 0f))));
        }
        return suggested;
    }

    /** The spot where the admin stands, relative to the map they are setting up. */
    public Optional<Point> here(Player admin) {
        Editing session = editing.get(admin.getUniqueId());
        if (session == null || !arena.isArenaWorld(admin.getWorld().getName())) {
            return Optional.empty();
        }
        Location at = admin.getLocation();
        ArenaWorldService.Built where = session.where();
        return Optional.of(new Point(round(at.getX() - where.originX()), round(at.getY() - where.originY()),
                round(at.getZ() - where.originZ()), Math.round(at.getYaw()), 0f));
    }

    private static double round(double value) {
        return Math.round(value * 2) / 2.0;
    }

    /** Changes the map being set up; false when nobody is setting one up or the change cannot be saved. */
    public boolean change(Player admin, java.util.function.UnaryOperator<CtfMap> change) {
        Editing session = editing.get(admin.getUniqueId());
        if (session == null) {
            return false;
        }
        Optional<CtfMap> map = maps.load(session.mapId());
        return map.isPresent() && maps.save(change.apply(map.get()));
    }

    public Optional<CtfMap> current(Player admin) {
        return editing(admin.getUniqueId()).flatMap(session -> maps.load(session.mapId()));
    }

    public void done(UUID admin) {
        editing.remove(admin);
    }

    public void forget(UUID admin) {
        editing.remove(admin);
    }

    public Path folder() {
        return maps.folder();
    }
}
