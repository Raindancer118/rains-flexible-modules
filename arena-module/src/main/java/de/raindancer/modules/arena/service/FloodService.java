package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Effect;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.SoundCue;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.ArenaSettings;
import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.model.Pit;
import de.raindancer.modules.arena.model.Place;
import de.raindancer.modules.arena.rules.BasinRule;
import de.raindancer.modules.arena.store.FloodLedger;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The flood of a naval battle, as a scene: thunder and a horn, rain for whoever is in it, waterfalls down the walls,
 * the water rising a layer at a time under the boats — and afterwards a whirlpool taking it away again.
 *
 * <h2>Why the pit is always put back exactly</h2>
 * Every block the flood touches — water and the barriers that seal gates and holes — is written to the
 * {@link FloodLedger} with what it was <em>before</em> the first drop, and the pit counts as busy until it is dry. A
 * server stopping mid-battle restores the pit at its next start from that file.
 */
public final class FloodService implements IArenaService {

    private static final Title.Times SLOW = Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2400),
            Duration.ofMillis(600));

    private final Plugin plugin;
    private final Server server;
    private final Effects effects;
    private final Messages messages;
    private final FloodLedger ledger;
    private final LogChannel log;
    private final BasinRule rule = new BasinRule();
    /** Pits with water in them, or about to have — the queue keeps off them. */
    private final Map<String, List<FloodLedger.Change>> flooded = new ConcurrentHashMap<>();
    /** Pits whose water is still rising; draining takes a pit out at once, so no late layer lands after it. */
    private final Set<String> rising = ConcurrentHashMap.newKeySet();
    private volatile ArenaSettings settings;

    public FloodService(Plugin plugin, Server server, Effects effects, Messages messages, FloodLedger ledger,
                        LogChannel log, ArenaSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.effects = effects;
        this.messages = messages;
        this.ledger = ledger;
        this.log = log;
        this.settings = settings;
        // Only where nothing is bound yet: an owner's own choice of sound stays.
        defineIfMissing(START, new SoundCue("event.raid.horn", 1.0f, 0.7f));
        defineIfMissing(THUNDER, new SoundCue("entity.lightning_bolt.thunder", 0.8f, 0.6f));
        defineIfMissing(LAYER, new SoundCue("item.bucket.empty", 1.0f, 0.6f));
        defineIfMissing(RISEN, new SoundCue("event.raid.horn", 1.0f, 1.2f));
        defineIfMissing(DRAIN, new SoundCue("block.bubble_column.whirlpool_ambient", 1.0f, 0.6f));
    }

    public static final String START = "arena:flood-start";
    public static final String THUNDER = "arena:flood-thunder";
    public static final String LAYER = "arena:flood-layer";
    public static final String RISEN = "arena:flood-risen";
    public static final String DRAIN = "arena:flood-drain";

    private void defineIfMissing(String cue, SoundCue sound) {
        if (!effects.isDefined(cue)) {
            effects.define(cue, Effect.of(sound));
        }
    }

    @Override
    public void settings(ArenaSettings fresh) {
        this.settings = fresh;
    }

    public Set<String> busyPits() {
        return Set.copyOf(flooded.keySet());
    }

    /** The feet layer of a pit: where its first spawn stands. */
    public static int feetY(Pit pit) {
        return (int) Math.floor(pit.spawns().getFirst().y());
    }

    // ------------------------------------------------------------------ planning

    /** Works out the basin on the pit's own thread; empty when the pit has no floor or would hold too much water. */
    public CompletableFuture<Optional<BasinRule.Basin>> plan(Pit pit) {
        CompletableFuture<Optional<BasinRule.Basin>> planned = new CompletableFuture<>();
        Box floor = pit.floor();
        World world = floor == null ? null : server.getWorld(floor.world());
        if (world == null || pit.spawns().isEmpty()) {
            planned.complete(Optional.empty());
            return planned;
        }
        Location centre = new Location(world, floor.centreX(), floor.minY(), floor.centreZ());
        Scheduling.region(plugin, centre, () -> {
            try {
                int from = feetY(pit);
                List<int[]> starts = pit.spawns().stream()
                        .map(spawn -> new int[]{(int) Math.floor(spawn.x()), (int) Math.floor(spawn.z())}).toList();
                BasinRule.Basin basin = rule.plan(floor, from, from + settings.navalDepthClamped() - 1, starts,
                        (x, y, z) -> open(world.getBlockAt(x, y, z)), 80_000);
                planned.complete(basin.layers().isEmpty() ? Optional.empty() : Optional.of(basin));
            } catch (RuntimeException failed) {
                log.error(failed, "The flood for pit {} could not be planned.", pit.id());
                planned.complete(Optional.empty());
            }
        });
        return planned;
    }

    private static boolean open(Block block) {
        Material type = block.getType();
        return type.isAir() || (!type.isSolid() && !block.isLiquid() && type != Material.BARRIER
                && type != Material.LIGHT && !type.name().endsWith("_SIGN") && !type.name().endsWith("_BANNER"));
    }

    // ------------------------------------------------------------------ rising

    /**
     * Fills the pit layer by layer as a scene, the ledger written first; {@code then} runs on the global thread once
     * the water is up. A ledger that cannot be written stops the flood before any block changes — {@code failed} runs.
     */
    public void rise(Pit pit, BasinRule.Basin basin, Supplier<Collection<Player>> audience, Runnable then,
                     Runnable failed) {
        World world = server.getWorld(pit.floor().world());
        if (world == null) {
            Scheduling.global(plugin, failed);
            return;
        }
        Location centre = new Location(world, pit.floor().centreX(), feetY(pit), pit.floor().centreZ());
        flooded.put(pit.id(), List.of());
        Scheduling.region(plugin, centre, () -> {
            List<FloodLedger.Change> changes = new ArrayList<>();
            for (BasinRule.Layer layer : basin.layers()) {
                for (BasinRule.Cell cell : layer.seals()) {
                    changes.add(change(world, cell));
                }
                for (BasinRule.Cell cell : layer.water()) {
                    changes.add(change(world, cell));
                }
            }
            Scheduling.async(plugin, () -> {
                if (!ledger.write(pit.id(), changes)) {
                    log.error("The flood ledger for pit {} could not be written, so the pit is not flooded.", pit.id());
                    flooded.remove(pit.id());
                    Scheduling.global(plugin, failed);
                    return;
                }
                flooded.put(pit.id(), List.copyOf(changes));
                rising.add(pit.id());
                Scheduling.global(plugin, () -> scene(pit, world, centre, basin, audience, then));
            });
        });
    }

    private static FloodLedger.Change change(World world, BasinRule.Cell cell) {
        return new FloodLedger.Change(world.getName(), cell.x(), cell.y(), cell.z(),
                world.getBlockAt(cell.x(), cell.y(), cell.z()).getBlockData().getAsString());
    }

    private void scene(Pit pit, World world, Location centre, BasinRule.Basin basin,
                       Supplier<Collection<Player>> audience, Runnable then) {
        Collection<Player> watching = audience.get();
        // The storm comes in.
        if (settings.navalRain()) {
            watching.forEach(player -> Scheduling.entity(plugin, player, () -> player.setPlayerWeather(WeatherType.DOWNFALL)));
        }
        playAll(watching, START);
        playAll(watching, THUNDER);
        watching.forEach(player -> Scheduling.entity(plugin, player, () -> player.showTitle(Title.title(
                messages.get("arena.naval.flood-title"), messages.get("arena.naval.flood-subtitle"), SLOW))));
        List<BasinRule.Cell> rim = rim(basin);
        Scheduling.region(plugin, centre, () -> {
            for (int strike = 0; strike < 3 && !rim.isEmpty(); strike++) {
                BasinRule.Cell at = rim.get(ThreadLocalRandom.current().nextInt(rim.size()));
                world.strikeLightningEffect(new Location(world, at.x() + 0.5, at.y() + 6, at.z() + 0.5));
            }
        });

        int layers = basin.layers().size();
        long lead = 40;
        long every = Math.max(10, settings.navalFloodTicks() / Math.max(1, layers));
        // Waterfalls down the walls the whole time the water rises.
        long pouring = lead + every * layers;
        for (long tick = 0; tick < pouring; tick += 5) {
            Scheduling.regionLater(plugin, centre, tick, () -> waterfalls(world, rim));
        }
        for (int index = 0; index < layers; index++) {
            BasinRule.Layer layer = basin.layers().get(index);
            boolean last = index == layers - 1;
            Scheduling.regionLater(plugin, centre, lead + every * index, () -> {
                if (!rising.contains(pit.id())) {
                    return;
                }
                layer.seals().forEach(cell -> world.getBlockAt(cell.x(), cell.y(), cell.z()).setType(Material.BARRIER, false));
                layer.water().forEach(cell -> world.getBlockAt(cell.x(), cell.y(), cell.z()).setType(Material.WATER, false));
                splash(world, layer);
                Collection<Player> now = audience.get();
                playAll(now, LAYER);
                if (last) {
                    rising.remove(pit.id());
                    playAll(now, RISEN);
                    now.forEach(player -> Scheduling.entity(plugin, player, () -> player.showTitle(Title.title(
                            messages.get("arena.naval.risen-title"), messages.get("arena.naval.risen-subtitle"), SLOW))));
                    Scheduling.globalLater(plugin, 30L, then);
                }
            });
        }
    }

    /** Water cells at the edge of the top layer — where the walls are, for waterfalls and lightning. */
    private static List<BasinRule.Cell> rim(BasinRule.Basin basin) {
        BasinRule.Layer top = basin.layers().getLast();
        Set<BasinRule.Cell> water = Set.copyOf(top.water());
        List<BasinRule.Cell> rim = new ArrayList<>();
        for (BasinRule.Cell cell : top.water()) {
            if (!water.contains(new BasinRule.Cell(cell.x() + 1, cell.y(), cell.z()))
                    || !water.contains(new BasinRule.Cell(cell.x() - 1, cell.y(), cell.z()))
                    || !water.contains(new BasinRule.Cell(cell.x(), cell.y(), cell.z() + 1))
                    || !water.contains(new BasinRule.Cell(cell.x(), cell.y(), cell.z() - 1))) {
                rim.add(cell);
            }
        }
        Collections.shuffle(rim);
        return rim;
    }

    private static void waterfalls(World world, List<BasinRule.Cell> rim) {
        for (int index = 0; index < Math.min(24, rim.size()); index++) {
            BasinRule.Cell at = rim.get(ThreadLocalRandom.current().nextInt(rim.size()));
            world.spawnParticle(Particle.FALLING_WATER, at.x() + 0.5, at.y() + 7, at.z() + 0.5, 12, 0.3, 3.0, 0.3, 0);
        }
    }

    private static void splash(World world, BasinRule.Layer layer) {
        List<BasinRule.Cell> cells = layer.water();
        for (int index = 0; index < Math.min(80, cells.size()); index++) {
            BasinRule.Cell at = cells.get(ThreadLocalRandom.current().nextInt(cells.size()));
            world.spawnParticle(Particle.SPLASH, at.x() + 0.5, at.y() + 1.0, at.z() + 0.5, 10, 0.4, 0.1, 0.4, 0.1);
            world.spawnParticle(Particle.BUBBLE_POP, at.x() + 0.5, at.y() + 0.9, at.z() + 0.5, 3, 0.3, 0.1, 0.3, 0.02);
        }
    }

    private void playAll(Collection<Player> players, String cue) {
        effects.playForAll(players.stream().map(Player::getUniqueId).toList(), cue);
    }

    // ------------------------------------------------------------------ draining

    /**
     * The water goes, top layer first, with a whirlpool; the barriers last. {@code then} runs once the pit is dry and
     * free again.
     */
    public void drain(Pit pit, Collection<UUID> rainedOn, Runnable then) {
        rising.remove(pit.id());
        rainedOn.stream().map(server::getPlayer).filter(java.util.Objects::nonNull)
                .forEach(player -> Scheduling.entity(plugin, player, player::resetPlayerWeather));
        List<FloodLedger.Change> changes = flooded.get(pit.id());
        World world = server.getWorld(pit.floor().world());
        if (changes == null || world == null) {
            flooded.remove(pit.id());
            Scheduling.global(plugin, then);
            return;
        }
        Location centre = new Location(world, pit.floor().centreX(), feetY(pit), pit.floor().centreZ());
        effects.playForAll(List.copyOf(rainedOn), DRAIN);
        // Highest first, so the water seems to sink away; everything put back as it was before.
        List<FloodLedger.Change> order = new ArrayList<>(changes);
        order.sort((a, b) -> Integer.compare(b.y(), a.y()));
        int steps = 4;
        int per = Math.max(1, (order.size() + steps - 1) / steps);
        for (int step = 0; step < steps; step++) {
            List<FloodLedger.Change> part = order.subList(Math.min(order.size(), step * per),
                    Math.min(order.size(), (step + 1) * per));
            boolean last = step == steps - 1;
            Scheduling.regionLater(plugin, centre, 10L + step * 10L, () -> {
                restore(world, part);
                if (!part.isEmpty()) {
                    FloodLedger.Change at = part.get(ThreadLocalRandom.current().nextInt(part.size()));
                    world.spawnParticle(Particle.BUBBLE_COLUMN_UP, at.x() + 0.5, at.y() + 0.5, at.z() + 0.5, 60, 3, 0.5, 3, 0.1);
                }
                if (last) {
                    // Anything a flood washed into the seals is put back too: the full ledger once more.
                    restore(world, changes);
                    Scheduling.async(plugin, () -> {
                        ledger.done(pit.id());
                        flooded.remove(pit.id());
                        Scheduling.global(plugin, then);
                    });
                }
            });
        }
    }

    private static void restore(World world, List<FloodLedger.Change> changes) {
        for (FloodLedger.Change change : changes) {
            try {
                world.getBlockAt(change.x(), change.y(), change.z()).setBlockData(Bukkit.createBlockData(change.before()), false);
            } catch (IllegalArgumentException unknown) {
                world.getBlockAt(change.x(), change.y(), change.z()).setType(Material.AIR, false);
            }
        }
    }

    /** At startup: any pit a stopped server left flooded is put back from its ledger. @return how many */
    public int recover() {
        int recovered = 0;
        for (String pit : ledger.pending()) {
            List<FloodLedger.Change> changes = ledger.read(pit);
            World world = changes.isEmpty() ? null : server.getWorld(changes.getFirst().world());
            if (world == null) {
                log.warn("Pit {} was left flooded, but its world is not loaded; it is restored once it is.", pit);
                continue;
            }
            FloodLedger.Change first = changes.getFirst();
            Scheduling.region(plugin, new Location(world, first.x(), first.y(), first.z()), () -> {
                restore(world, changes);
                Scheduling.async(plugin, () -> ledger.done(pit));
            });
            recovered++;
        }
        return recovered;
    }

    /** At shutdown: every flooded pit is put back now, on this thread — there is no later. */
    public void drainNow() {
        for (Map.Entry<String, List<FloodLedger.Change>> entry : Map.copyOf(flooded).entrySet()) {
            List<FloodLedger.Change> changes = entry.getValue();
            if (changes.isEmpty()) {
                continue;
            }
            World world = server.getWorld(changes.getFirst().world());
            if (world != null && !Scheduling.isFolia()) {
                restore(world, changes);
                ledger.done(entry.getKey());
            }
            // On Folia the ledger stays, and the next start restores the pit.
        }
        flooded.clear();
    }

    /** For spawning: the place a boat starts, in a basin's lowest layer. */
    public static Place spot(Pit pit, int x, int z, float yaw) {
        return new Place(pit.floor().world(), x + 0.5, feetY(pit), z + 0.5, yaw, 0);
    }
}
