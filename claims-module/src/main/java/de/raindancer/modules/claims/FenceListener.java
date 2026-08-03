package de.raindancer.modules.claims;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Optional;

/**
 * Keeps the fence bookkeeping in step with what the owner does by hand.
 * <p>
 * The owner is not forced through a GUI to change their fence: they break a panel and the gap is
 * remembered, they place a gate and it is adopted. Both survive the claim being reshaped later, which is
 * the whole point — a resize must not undo somebody's front door.
 * <p>
 * Runs at {@code MONITOR} so it only reacts to changes the protection listeners already allowed.
 */
public final class FenceListener implements Listener {

    private final ClaimServices services;

    public FenceListener(ClaimServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!services.fences().featureAvailable()) {
            return;
        }
        Block block = event.getBlock();
        if (!ClaimFence.isFence(block.getType()) && !ClaimFence.isGate(block.getType())) {
            return;
        }
        Optional<Claim> claim = services.claims().at(block.getLocation());
        if (claim.isEmpty()) {
            return;
        }
        services.fences().handleBreak(claim.get(), block.getX(), block.getY(), block.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!services.fences().featureAvailable()) {
            return;
        }
        Block block = event.getBlock();
        if (!ClaimFence.isFence(block.getType()) && !ClaimFence.isGate(block.getType())) {
            return;
        }
        Optional<Claim> claim = services.claims().at(block.getLocation());
        if (claim.isEmpty()) {
            return;
        }
        Claim target = claim.get();
        // Only owners and claim admins shape the official fence; a trusted builder's decorative fence
        // elsewhere in the claim must not silently become plugin managed.
        if (!services.rights().canManage(target, event.getPlayer(),
                ClaimAdminPermission.MANAGE_SHAPE)
                && !target.isOwner(event.getPlayer().getUniqueId())) {
            return;
        }
        if (services.fences().handlePlace(target, block.getX(), block.getY(), block.getZ(), block.getType())
                && ClaimFence.isGate(block.getType())) {
            services.messages().send(event.getPlayer(), "fence.gate-adopted",
                    "claim", target.name());
        }
    }
}
