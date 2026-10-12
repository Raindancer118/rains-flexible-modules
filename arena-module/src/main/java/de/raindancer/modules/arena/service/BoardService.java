package de.raindancer.modules.arena.service;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.arena.model.Stats;
import de.raindancer.modules.arena.rules.BoardRule;
import de.raindancer.modules.arena.store.StatsStore;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The leaderboards floating over the hub. Where they stand is kept in {@code boards.yml}; each is a text display marked
 * as the arena's, found again at its spot after a restart (or put back if it is gone) and given fresh text every half
 * minute — the records read off the server thread.
 */
public final class BoardService {

    private static final double FIND = 1.5;

    private final Plugin plugin;
    private final Server server;
    private final StatsStore stats;
    private final YamlStore file;
    private final NamespacedKey mark;
    private final BoardRule rule = new BoardRule();
    private final List<Location> boards = new CopyOnWriteArrayList<>();
    private volatile Component text = Component.empty();
    private ScheduledTask timer;

    public BoardService(Plugin plugin, Server server, StatsStore stats, Path file) {
        this.plugin = plugin;
        this.server = server;
        this.stats = stats;
        this.file = new YamlStore(file);
        this.mark = new NamespacedKey(plugin, "board");
    }

    public void start() {
        for (String line : file.read().getStringList("boards")) {
            String[] parts = line.split(" ");
            World world = parts.length == 4 ? server.getWorld(parts[0]) : null;
            if (world != null) {
                try {
                    boards.add(new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[3])));
                } catch (NumberFormatException garbled) {
                    // A line nobody can read is a board nobody can find; it is left out.
                }
            }
        }
        timer = Scheduling.asyncTimer(plugin, 5, 30, task -> refresh());
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
        }
    }

    /** A board where the admin stands, a little above their head. */
    public void place(Location at) {
        Location spot = at.clone().add(0, 2.2, 0);
        spot.setYaw(0);
        spot.setPitch(0);
        boards.add(spot);
        save();
        Scheduling.async(plugin, this::refresh);
    }

    /** The board nearest to the admin, within five blocks. */
    public boolean removeNearest(Player admin) {
        Optional<Location> nearest = boards.stream()
                .filter(spot -> spot.getWorld().equals(admin.getWorld()) && spot.distance(admin.getLocation()) <= 5)
                .min(java.util.Comparator.comparingDouble(spot -> spot.distance(admin.getLocation())));
        nearest.ifPresent(spot -> {
            boards.remove(spot);
            save();
            Scheduling.region(plugin, spot, () -> marked(spot).forEach(TextDisplay::remove));
        });
        return nearest.isPresent();
    }

    private void refresh() {
        List<Stats> byWins = stats.top(false, 5);
        List<Stats> byWave = stats.top(true, 5);
        text = MiniMessage.miniMessage().deserialize(rule.text(byWins, byWave));
        for (Location spot : boards) {
            Scheduling.region(plugin, spot, () -> show(spot));
        }
    }

    private void show(Location spot) {
        if (!spot.isChunkLoaded()) {
            return;
        }
        List<TextDisplay> found = marked(spot);
        if (found.isEmpty()) {
            found = List.of(spot.getWorld().spawn(spot, TextDisplay.class, display -> {
                display.getPersistentDataContainer().set(mark, PersistentDataType.BYTE, (byte) 1);
                display.setBillboard(Display.Billboard.VERTICAL);
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setBackgroundColor(Color.fromARGB(150, 10, 6, 20));
                display.setShadowed(true);
                display.setLineWidth(260);
                display.setPersistent(true);
            }));
        }
        found.forEach(display -> display.text(text));
    }

    private List<TextDisplay> marked(Location spot) {
        List<TextDisplay> out = new ArrayList<>();
        for (TextDisplay display : spot.getNearbyEntitiesByType(TextDisplay.class, FIND)) {
            if (display.getPersistentDataContainer().has(mark, PersistentDataType.BYTE)) {
                out.add(display);
            }
        }
        return out;
    }

    private void save() {
        List<String> lines = boards.stream().map(spot -> String.format(Locale.ROOT, "%s %.2f %.2f %.2f",
                spot.getWorld().getName(), spot.getX(), spot.getY(), spot.getZ())).toList();
        file.write(yaml -> yaml.set("boards", lines));
    }
}
