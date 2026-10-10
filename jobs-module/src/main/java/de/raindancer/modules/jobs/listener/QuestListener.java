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
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG, CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,
            CreatureSpawnEvent.SpawnReason.COMMAND, CreatureSpawnEvent.SpawnReason.CUSTOM);

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
            count(player, QuestTask.HARVEST, type, 1);
        }
        if (PlacedBlocks.watching(block.getType()) && !PlacedBlocks.isPlaced(block)) {
            count(player, QuestTask.MINE, type, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player || !playing(killer) || FARMED.contains(dead.getEntitySpawnReason())) {
            return;
        }
        count(killer, QuestTask.KILL, dead.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof Item item
                && playing(event.getPlayer())) {
            count(event.getPlayer(), QuestTask.FISH, item.getItemStack().getType().name(),
                    item.getItemStack().getAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player && playing(player)) {
            count(player, QuestTask.BREED, event.getEntity().getType().name(), 1);
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
                || Away.isKnown() && Away.isAway(player.getUniqueId()) || !lookedAround(before, here)) {
            return;
        }
        double distance = Math.hypot(here.getX() - before.getX(), here.getZ() - before.getZ());
        if (distance <= MOST_PER_SAMPLE) {
            services.orders().progress(player, QuestTask.TRAVEL, "", services.quests().travelled(player, distance));
        }
    }

    /**
     * Whether the view turned between two samples. A water stream, a minecart loop or a piston clock carries an
     * idle player for hours without the camera ever moving; somebody actually travelling turns it all the time.
     */
    public static boolean lookedAround(Location before, Location here) {
        return Math.abs(before.getYaw() - here.getYaw()) > 0.5f || Math.abs(before.getPitch() - here.getPitch()) > 0.5f;
    }

    /** Counts for the day's quests and for the running order alike. */
    private void count(Player player, QuestTask task, String thing, int count) {
        services.quests().progress(player, task, thing, count);
        services.orders().progress(player, task, thing, count);
    }

    @Override
    public void forget(UUID player) {
        lastSeen.remove(player);
        services.quests().forget(player);
        services.orders().forget(player);
    }
}
