package de.raindancer.modules.performance.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.performance.PerformanceSettings;
import de.raindancer.modules.performance.model.ChunkCensus;
import de.raindancer.modules.performance.model.Finding;
import de.raindancer.modules.performance.model.Fix;
import de.raindancer.modules.performance.rules.ThinRule;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Carries out what staff chose from a report. Works on the chunk's own region thread, removes only what
 * the fix names, and says how many it removed. Nothing here runs without somebody asking.
 */
public final class FixService implements IPerformanceService {

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    private final LogChannel log;
    private final ThinRule thin = new ThinRule();
    /** Each world's simulation distance before a fix changed it, for the undo. */
    private final Map<String, Integer> distanceBefore = new HashMap<>();

    public FixService(Plugin plugin, Server server, Messages messages, LogChannel log) {
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.log = log;
    }

    @Override
    public void settings(PerformanceSettings settings) {
        // Nothing to swap: every fix carries its own numbers, decided when the report was made.
    }

    /** Applies the fixes of a finding that act on its chunk; {@code done} hears each result line's key and values. */
    public void apply(Finding finding, World world, CommandSender by, Consumer<Object[]> done) {
        ChunkCensus where = finding.where();
        Location middle = new Location(world, where.blockX(), 64, where.blockZ());
        Scheduling.region(plugin, middle, () -> {
            if (!world.isChunkLoaded(where.chunkX(), where.chunkZ())) {
                done.accept(new Object[]{"performance.fix-unloaded"});
                return;
            }
            Chunk chunk = world.getChunkAt(where.chunkX(), where.chunkZ());
            for (Fix fix : finding.fixes()) {
                switch (fix.action()) {
                    case THIN_ANIMALS -> done.accept(new Object[]{"performance.fixed-thinned", "count",
                            thin(chunk, fix.what(), fix.amount()), "kind", fix.what(), "keep", fix.amount()});
                    case CLEAR_ITEMS -> done.accept(new Object[]{"performance.fixed-items", "count", clearItems(chunk)});
                    case CLEAR_MONSTERS -> done.accept(new Object[]{"performance.fixed-monsters", "count", clearMonsters(chunk)});
                    case TELL_OWNER -> done.accept(new Object[]{"performance.fixed-told", "count", tellOwners(finding)});
                    default -> { }
                }
            }
            log.info("{} applied the fixes for {} {} at {} {} in {}.", by.getName(), finding.count(), finding.what(),
                    where.blockX(), where.blockZ(), where.world());
        });
    }

    private int thin(Chunk chunk, String type, int keep) {
        Map<UUID, Entity> byId = new HashMap<>();
        List<ThinRule.Animal> animals = new ArrayList<>();
        for (Entity entity : chunk.getEntities()) {
            if (!Census.typeOf(entity).equals(type) || !(entity instanceof LivingEntity living)) {
                continue;
            }
            boolean baby = entity instanceof Ageable ageable && !ageable.isAdult();
            boolean kept = entity.customName() != null || living.isLeashed()
                    || (entity instanceof Tameable tameable && tameable.isTamed());
            animals.add(new ThinRule.Animal(entity.getUniqueId(), baby, kept));
            byId.put(entity.getUniqueId(), entity);
        }
        List<UUID> gone = thin.toRemove(animals, keep);
        gone.forEach(id -> byId.get(id).remove());
        return gone.size();
    }

    private static int clearItems(Chunk chunk) {
        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item || entity instanceof ExperienceOrb) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Only monsters the game would despawn anyway: not named, not leashed, not kept from despawning. */
    private static int clearMonsters(Chunk chunk) {
        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Enemy && entity instanceof LivingEntity living && living.getRemoveWhenFarAway()
                    && entity.customName() == null && !living.isLeashed()) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    private int tellOwners(Finding finding) {
        int told = 0;
        for (UUID owner : finding.owners()) {
            Player player = server.getPlayer(owner);
            if (player != null) {
                Scheduling.entity(plugin, player, () -> messages.send(player, "performance.owner-told",
                        "count", finding.count(), "kind", finding.what(), "claim", finding.landName(),
                        "x", finding.where().blockX(), "z", finding.where().blockZ()));
                told++;
            }
        }
        return told;
    }

    /** Sets every world's simulation distance; remembers what it was the first time, for {@link #undoDistance}. */
    public void simulationDistance(int chunks, CommandSender by) {
        Scheduling.global(plugin, () -> {
            for (World world : server.getWorlds()) {
                distanceBefore.putIfAbsent(world.getKey().asString(), world.getSimulationDistance());
                world.setSimulationDistance(chunks);
            }
            log.info("{} set the simulation distance of every world to {} until the next restart.", by.getName(), chunks);
        });
    }

    /** @return whether there was anything to undo */
    public boolean undoDistance(CommandSender by) {
        if (distanceBefore.isEmpty()) {
            return false;
        }
        Map<String, Integer> before = new HashMap<>(distanceBefore);
        distanceBefore.clear();
        Scheduling.global(plugin, () -> {
            Set<String> done = new HashSet<>();
            for (World world : server.getWorlds()) {
                Integer was = before.get(world.getKey().asString());
                if (was != null) {
                    world.setSimulationDistance(was);
                    done.add(world.getName());
                }
            }
            log.info("{} put the simulation distance back in {}.", by.getName(), done);
        });
        return true;
    }

    @Override
    public String describe() {
        return "applies a report's fixes on the chunk's own thread";
    }
}
