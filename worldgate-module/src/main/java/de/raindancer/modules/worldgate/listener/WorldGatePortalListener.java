package de.raindancer.modules.worldgate.listener;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.worldgate.model.Dimension;
import de.raindancer.modules.worldgate.model.GateState;
import de.raindancer.modules.worldgate.rules.GateRule;
import de.raindancer.modules.worldgate.service.WorldGateService;
import de.raindancer.modules.worldgate.util.PermissionNodes;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerPortalEvent;

/**
 * Keeps a locked Nether or End actually locked.
 *
 * <h2>Why this follows {@code FarmWorldPortalListener}'s exact shape</h2>
 * {@code HIGH} rather than {@code MONITOR} so the destination — or, here, the cancellation — can still
 * be changed by this handler; a {@code MONITOR} handler that edits the event is the classic way to
 * have no effect at all. {@code ignoreCancelled} because a portal another plugin has already cancelled
 * is not ours to un-cancel.
 *
 * <h2>Ownership, both directions</h2>
 * Only a portal that touches one of the two configured worlds — as either where the player is coming
 * from or where the event's own destination resolves to — is ever looked at. Everything else on the
 * server behaves exactly as it always did, which matters more than catching every crossing: a listener
 * that quietly meddles with somebody else's portal is worse than one that misses a few of ours.
 */
public final class WorldGatePortalListener implements IWorldGateListener {

    private final WorldGateService service;
    private final GateRule rule;
    private final Messages messages;

    public WorldGatePortalListener(WorldGateService service, GateRule rule, Messages messages) {
        this.service = service;
        this.rule = rule;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        String fromWorld = worldNameOf(event.getFrom());
        String toWorld = worldNameOf(event.getTo());

        Dimension entering = ownedBy(toWorld);
        Dimension leaving = ownedBy(fromWorld);
        if (entering == null && leaving == null) {
            // Not ours: leave it exactly as it was.
            return;
        }

        // Leaving a managed, blocked dimension is always allowed regardless of state — GateRule
        // already answers true for entering == false, so there is nothing to check or cancel for the
        // leaving direction. Only an actual entry into a managed dimension can be refused.
        if (entering == null) {
            return;
        }

        Player player = event.getPlayer();
        boolean hasBypass = player.hasPermission(PermissionNodes.BYPASS);
        GateState state = service.state(entering);

        if (rule.allowed(state, true, hasBypass)) {
            return;
        }
        event.setCancelled(true);
        messages.send(player, state == GateState.CLOSED
                        ? "worldgate.closed-entry-denied" : "worldgate.drained-entry-denied",
                "dimension", entering.label());
    }

    private static String worldNameOf(Location location) {
        return location == null || location.getWorld() == null ? null : location.getWorld().getName();
    }

    private Dimension ownedBy(String worldName) {
        if (worldName == null) {
            return null;
        }
        if (worldName.equalsIgnoreCase(service.worldName(Dimension.NETHER))) {
            return Dimension.NETHER;
        }
        if (worldName.equalsIgnoreCase(service.worldName(Dimension.END))) {
            return Dimension.END;
        }
        return null;
    }

    @Override
    public String describe() {
        return "cancelling entry into a locked Nether or End for anybody without the bypass permission";
    }
}
