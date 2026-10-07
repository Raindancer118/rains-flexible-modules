package de.raindancer.modules.anticheat.listener;

import de.raindancer.modules.anticheat.AntiCheatServices;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Flag;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Locale;

/** Digging, breaking, placing, using blocks, and fishing. */
public final class WorldListener implements IAntiCheatListener {

    private final AntiCheatServices services;

    public WorldListener(AntiCheatServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDig(BlockDamageEvent event) {
        if (services.world().startDigging(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbort(BlockDamageAbortEvent event) {
        services.world().stopDigging(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (services.world().broke(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (services.world().placed(event.getPlayer(), event.getBlockPlaced(), event.getBlockAgainst(), event.getBlockReplacedState())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlaceRefused(BlockPlaceEvent event) {
        if (event.isCancelled()) {
            services.world().refusedPlacement(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR) {
                PlayerTrack track = services.tracks().of(event.getPlayer());
                synchronized (track) {
                    track.combat.lastUseMillis = track.now();
                }
            }
            return;
        }
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        if (services.world().used(event.getPlayer(), event.getClickedBlock())) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    /** A human sees the bobber dip after half a ping, needs a few hundred milliseconds, and the click takes another half. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        PlayerTrack track = services.tracks().of(event.getPlayer());
        long now = track.now();
        Double reaction = null;
        synchronized (track) {
            PlayerTrack.Combat c = track.combat;
            if (event.getState() == PlayerFishEvent.State.BITE) {
                c.fishBiteMillis = now;
            } else if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && c.fishBiteMillis > 0) {
                c.fishReactions.add(now - c.fishBiteMillis - track.ping);
                c.fishBiteMillis = 0;
                if (c.fishReactions.full()) {
                    double[] all = c.fishReactions.toArray();
                    double sum = 0;
                    int quick = 0;
                    for (double value : all) {
                        sum += value;
                        if (value < 120) {
                            quick++;
                        }
                    }
                    if (quick >= all.length - 1) {
                        reaction = sum / all.length;
                        c.fishReactions.clear();
                    }
                }
            }
        }
        if (reaction != null && services.violations().runs(track, CheckType.AUTO_FISH)) {
            services.violations().flag(event.getPlayer(), track, Flag.of(CheckType.AUTO_FISH,
                    String.format(Locale.ROOT, "reeled in ten bites in %.0f ms each, after ping", reaction)));
        }
    }

    @Override
    public String describe() {
        return "judging digging, placing, using blocks and fishing";
    }
}
