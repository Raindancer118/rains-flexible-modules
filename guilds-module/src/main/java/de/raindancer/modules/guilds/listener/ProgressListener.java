package de.raindancer.modules.guilds.listener;

import de.raindancer.core.world.blocks.PlacedBlocks;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Activity;
import de.raindancer.modules.guilds.model.XpSource;
import de.raindancer.modules.guilds.rules.ActivityRule;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.raid.RaidFinishEvent;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * What members do that earns their guild experience and counts towards its contracts. Each event becomes one
 * {@link Activity}; what it is worth and whether it counts is decided past here. Only what really happened counts:
 * events another plugin cancelled are ignored, and an ore or melon a player placed is never "mined" or "harvested".
 */
public final class ProgressListener implements IGuildsListener {

    private static final Set<EntityType> BOSSES = Set.of(EntityType.WITHER, EntityType.ENDER_DRAGON,
            EntityType.ELDER_GUARDIAN, EntityType.WARDEN);

    private final GuildsServices services;

    public ProgressListener(GuildsServices services) {
        this.services = services;
    }

    @Override
    public void forget(UUID player) {
        services.xp().forget(player);
    }

    private void did(Player player, Activity activity) {
        services.xp().activity(player.getUniqueId(), activity);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player) {
            return;
        }
        String what = dead.getType().getKey().getKey();
        if (BOSSES.contains(dead.getType())) {
            did(killer, Activity.of(XpSource.BOSS, what));
        } else if (dead instanceof Enemy) {
            did(killer, Activity.of(XpSource.MONSTER, what));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKilled(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null && !killer.equals(event.getEntity())) {
            services.xp().rivalDefeated(killer.getUniqueId(), event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        var display = event.getAdvancement().getDisplay();
        if (display != null && display.doesAnnounceToChat()) {
            did(event.getPlayer(), Activity.of(XpSource.ADVANCEMENT, event.getAdvancement().getKey().getKey()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        var ore = ActivityRule.oreOf(type);
        if (ore.isPresent()) {
            if (!PlacedBlocks.isPlaced(block)) {
                did(event.getPlayer(), Activity.of(XpSource.MINE, ore.get()));
            }
            return;
        }
        if (ActivityRule.AGED_CROPS.contains(type) && block.getBlockData() instanceof Ageable crop
                && crop.getAge() >= crop.getMaximumAge()) {
            did(event.getPlayer(), Activity.of(XpSource.HARVEST, type.getKey().getKey()));
        } else if (ActivityRule.GROWN_BLOCKS.contains(type) && !PlacedBlocks.isPlaced(block)) {
            did(event.getPlayer(), Activity.of(XpSource.HARVEST, type.name().toLowerCase(Locale.ROOT)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            did(event.getPlayer(), Activity.of(XpSource.FISH, 1));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player breeder) {
            did(breeder, Activity.of(XpSource.BREED, event.getEntityType().getKey().getKey()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        did(event.getPlayer(), Activity.of(XpSource.TRADE, 1));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        did(event.getEnchanter(), Activity.of(XpSource.ENCHANT, event.whichButton() + 1));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRaid(RaidFinishEvent event) {
        for (Player hero : event.getWinners()) {
            did(hero, Activity.of(XpSource.RAID, 1));
        }
    }
}
