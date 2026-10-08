package de.raindancer.modules.moderation.listener;

import de.raindancer.modules.moderation.service.HoneypotService;
import de.raindancer.modules.moderation.service.OreDensitySampler;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.UUID;

/**
 * Keeps the bait ores honest — nothing ever shows one to a player who could see it without an
 * x-ray — and offers loading chunks to the density sampler.
 */
public final class XrayBaitListener implements IModerationListener {

    private final HoneypotService honeypots;
    private final OreDensitySampler sampler;

    public XrayBaitListener(HoneypotService honeypots, OreDensitySampler sampler) {
        this.honeypots = honeypots;
        this.sampler = sampler;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        honeypots.start(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        honeypots.forget(event.getPlayer().getUniqueId());
    }

    /** A dig next to a bait turns it back before the client can show it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDigStart(BlockDamageEvent event) {
        honeypots.digStarted(event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        honeypots.changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(honeypots::changed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(honeypots::changed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent event) {
        event.getBlocks().forEach(honeypots::changed);
        honeypots.changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent event) {
        event.getBlocks().forEach(honeypots::changed);
        honeypots.changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        honeypots.changed(to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        honeypots.changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkGone(PlayerChunkUnloadEvent event) {
        honeypots.chunkGone(event.getPlayer(), event.getChunk().getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        sampler.offer(event.getChunk());
    }

    @Override
    public void forget(UUID player) {
        honeypots.forget(player);
    }

    @Override
    public String describe() {
        return "keeping bait ores out of honest sight and sampling the world's ore";
    }
}
