package de.raindancer.modules.wallsroads.listener;

import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.Gate;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.rules.GateRule;
import de.raindancer.modules.wallsroads.util.PermissionNodes;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Optional;

/**
 * Opening and shutting a gate by hand — right-click the gate itself, which is where somebody
 * standing in front of a closed gate will try first.
 */
public final class GateListener implements IWallsRoadsListener {

    private final WallsRoadsServices services;
    private final GateRule rule = new GateRule();

    public GateListener(WallsRoadsServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        Spot spot = new Spot(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());

        for (Wall wall : services.registry().wallsIn(block.getWorld().getName())) {
            Optional<Gate> found = services.service().gates().gateAt(wall, spot);
            if (found.isEmpty()) {
                continue;
            }
            Gate gate = found.get();
            if (gate.sealed()) {
                return;
            }
            event.setCancelled(true);
            if (!rule.mayOperate(wall.gatesOpenToEveryone(), wall.owner(), player.getUniqueId(),
                    player.hasPermission(PermissionNodes.MANAGE_ANY))) {
                services.messages().send(player, "wallsroads.gate.not-yours", "wall", wall.name());
                return;
            }
            if (gate.shut()) {
                services.service().openGate(wall, gate.id(), () ->
                        services.messages().send(player, "wallsroads.gate.opened", "wall", wall.name()));
            } else {
                services.service().shutGate(wall, gate.id(), () ->
                        services.messages().send(player, "wallsroads.gate.shut", "wall", wall.name()));
            }
            return;
        }
    }
}
