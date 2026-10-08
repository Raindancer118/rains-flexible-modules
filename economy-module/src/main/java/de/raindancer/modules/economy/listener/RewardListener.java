package de.raindancer.modules.economy.listener;

import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.store.PlacedBlocks;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.UUID;

/** What earns money while playing. MONITOR and ignoring cancelled events: only what really happened pays. */
public final class RewardListener implements IEconomyListener {

    private final EconomyServices services;

    public RewardListener(EconomyServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || victim instanceof Player) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = victim.getEntitySpawnReason();
        boolean fromSpawner = reason == CreatureSpawnEvent.SpawnReason.SPAWNER
                || reason == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER;
        services.rewards().killed(killer, victim.getType(), fromSpawner);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (services.rewards().pays(event.getBlockPlaced().getType())) {
            PlacedBlocks.mark(event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!services.rewards().pays(event.getBlock().getType())) {
            return;
        }
        boolean placed = PlacedBlocks.consume(event.getBlock());
        services.rewards().mined(event.getPlayer(), event.getBlock().getType(), placed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        var display = event.getAdvancement().getDisplay();
        if (display == null || !display.doesAnnounceToChat()) {
            return;
        }
        String title = PlainTextComponentSerializer.plainText().serialize(display.title());
        services.rewards().advanced(event.getPlayer(), title);
    }

    @Override
    public void forget(UUID player) {
        // The hourly windows forget themselves when their hour is up.
    }

    @Override
    public String describe() {
        return "mobs killed, ores mined and advancements made";
    }
}
