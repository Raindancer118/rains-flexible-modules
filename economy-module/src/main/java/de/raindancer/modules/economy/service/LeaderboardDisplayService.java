package de.raindancer.modules.economy.service;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Account;
import de.raindancer.modules.economy.model.Spot;
import de.raindancer.modules.economy.rules.LeaderboardRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Floating lists of the richest players, put down in the world. Each is a text display tagged as this
 * module's; the places live in the settings, so a lost display is simply put back on the next refresh.
 * Everything touching the world happens on the region that owns it.
 */
public final class LeaderboardDisplayService implements IEconomyService {

    public static final NamespacedKey TAG = Objects.requireNonNull(NamespacedKey.fromString("rainseconomy:leaderboard"));
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final Server server;
    private final LeaderboardService leaderboard;
    private final SettingsStore<EconomySettings> store;
    private final LeaderboardRule rule = new LeaderboardRule();
    private volatile EconomySettings settings;

    public LeaderboardDisplayService(Plugin plugin, Server server, LeaderboardService leaderboard,
                                     SettingsStore<EconomySettings> store, EconomySettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.leaderboard = leaderboard;
        this.store = store;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public List<Spot> spots() {
        List<Spot> read = new ArrayList<>();
        settings.leaderboardSpots().forEach(line -> Spot.parse(line).ifPresent(read::add));
        return read;
    }

    /** Redraws every leaderboard whose ground is loaded. */
    public void refresh() {
        EconomySettings live = settings;
        if (!live.leaderboardsEnabled()) {
            return;
        }
        Component text = text(live);
        for (Spot spot : spots()) {
            World world = server.getWorld(spot.world());
            if (world == null) {
                continue;
            }
            Location at = new Location(world, spot.x(), spot.y(), spot.z());
            Scheduling.region(plugin, at, () -> {
                if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                    return;
                }
                TextDisplay display = find(at).orElseGet(() -> spawn(at));
                display.text(text);
            });
        }
    }

    private Component text(EconomySettings live) {
        Currency currency = live.currency();
        var built = Component.text().append(MINI.deserialize("<gold><bold>Richest players</bold></gold>"));
        List<Account> top = rule.top(leaderboard.ranking(), live.leaderboardSize());
        if (top.isEmpty()) {
            built.append(Component.newline()).append(MINI.deserialize("<gray>Nobody yet"));
        }
        for (int i = 0; i < top.size(); i++) {
            built.append(Component.newline())
                    .append(MINI.deserialize(rule.colourOf(i + 1) + (i + 1) + ". <white>"
                            + MINI.escapeTags(top.get(i).name()) + "<gray> — "))
                    .append(currency.render(top.get(i).balance()));
        }
        return built.build();
    }

    private static Optional<TextDisplay> find(Location at) {
        for (Entity nearby : at.getWorld().getNearbyEntities(at, 0.75, 0.75, 0.75)) {
            if (nearby instanceof TextDisplay display
                    && display.getPersistentDataContainer().has(TAG, PersistentDataType.BYTE)) {
                return Optional.of(display);
            }
        }
        return Optional.empty();
    }

    private static TextDisplay spawn(Location at) {
        return at.getWorld().spawn(at, TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(true);
            display.setShadowed(true);
            display.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** A new leaderboard just above the player's head. */
    public Spot place(Player player) {
        Location eye = player.getEyeLocation();
        Spot spot = new Spot(eye.getWorld().getName(), eye.getX(), eye.getY() + 0.75, eye.getZ());
        List<String> lines = new ArrayList<>(settings.leaderboardSpots());
        lines.add(spot.written());
        write(lines);
        Scheduling.entityLater(plugin, player, 2L, this::refresh);
        return spot;
    }

    /** Takes away the nearest leaderboard within a few blocks; whether there was one. */
    public boolean remove(Player player) {
        Location at = player.getLocation();
        Spot nearest = null;
        double best = 25;
        for (Spot spot : spots()) {
            double distance = spot.distanceSquared(at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
            if (distance < best) {
                best = distance;
                nearest = spot;
            }
        }
        if (nearest == null) {
            return false;
        }
        Spot gone = nearest;
        List<String> lines = new ArrayList<>();
        for (String line : settings.leaderboardSpots()) {
            if (!Spot.parse(line).map(gone::equals).orElse(false)) {
                lines.add(line);
            }
        }
        write(lines);
        Location there = new Location(at.getWorld(), gone.x(), gone.y(), gone.z());
        Scheduling.region(plugin, there, () -> find(there).ifPresent(Entity::remove));
        return true;
    }

    private void write(List<String> lines) {
        store.set("leaderboard.spots", String.join(", ", lines));
        store.trySave();
    }
}
