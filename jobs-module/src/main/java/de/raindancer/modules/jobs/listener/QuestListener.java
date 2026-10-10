package de.raindancer.modules.jobs.listener;

import de.raindancer.core.social.presence.Away;
import de.raindancer.core.world.blocks.PlacedBlocks;
import de.raindancer.modules.jobs.JobsServices;
import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.util.PermissionNodes;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Counts what players do towards their quests: blocks, crops, kills, catches, breeding and distance. */
public final class QuestListener implements IJobsListener {

    /** Further than this between two samples is a teleport, not a journey. */
    public static final double MOST_PER_SAMPLE = 500;
    /** Mobs that do not count: bred in a box, or bought as an egg and hatched to be killed. */
    private static final Set<CreatureSpawnEvent.SpawnReason> FARMED = Set.of(CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG, CreatureSpawnEvent.SpawnReason.DISPENSE_EGG);

    private final JobsServices services;
    private final Map<UUID, Location> lastSeen = new ConcurrentHashMap<>();

    public QuestListener(JobsServices services) {
        this.services = services;
    }

    private static boolean playing(Player player) {
        return (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && player.hasPermission(PermissionNodes.USE);
    }

    // Before MONITOR: Core's PlacedBlocks takes the mark off at MONITOR.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!playing(player)) {
            return;
        }
        Block block = event.getBlock();
        String type = block.getType().name();
        if (block.getBlockData() instanceof Ageable crop && crop.getAge() >= crop.getMaximumAge()) {
            services.quests().progress(player, QuestTask.HARVEST, type, 1);
        }
        if (PlacedBlocks.watching(block.getType()) && !PlacedBlocks.isPlaced(block)) {
            services.quests().progress(player, QuestTask.MINE, type, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player || !playing(killer) || FARMED.contains(dead.getEntitySpawnReason())) {
            return;
        }
        services.quests().progress(killer, QuestTask.KILL, dead.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof Item item
                && playing(event.getPlayer())) {
            services.quests().progress(event.getPlayer(), QuestTask.FISH, item.getItemStack().getType().name(),
                    item.getItemStack().getAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player && playing(player)) {
            services.quests().progress(player, QuestTask.BREED, event.getEntity().getType().name(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        de.raindancer.core.platform.util.Scheduling.entityLater(services.plugin(), player, 20L * 10, () -> {
            if (!player.isOnline() || !services.quests().settings().enabled() || !services.quests().settings().tellOnJoin()
                    || !player.hasPermission(PermissionNodes.USE)) {
                return;
            }
            long open = services.quests().today(player.getUniqueId()).quests().stream()
                    .filter(quest -> quest.open() && !quest.done()).count();
            if (open > 0) {
                services.messages().send(player, "jobs.quest.waiting", "count", String.valueOf(open),
                        "tier", String.valueOf(services.quests().today(player.getUniqueId()).tier()));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** One sample of where a player is, for travel quests. On the player's own thread. */
    public void sample(Player player) {
        Location here = player.getLocation();
        Location before = lastSeen.put(player.getUniqueId(), here);
        if (before == null || before.getWorld() != here.getWorld() || !playing(player) || player.isFlying()
                || Away.isKnown() && Away.isAway(player.getUniqueId())) {
            return;
        }
        double distance = Math.hypot(here.getX() - before.getX(), here.getZ() - before.getZ());
        if (distance <= MOST_PER_SAMPLE) {
            services.quests().travelled(player, distance);
        }
    }

    @Override
    public void forget(UUID player) {
        lastSeen.remove(player);
        services.quests().forget(player);
    }
}
