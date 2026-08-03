package de.raindancer.modules.claims.listener;

import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.selection.Selection;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Turns Claimborder Selection Stick clicks into selection changes. */
public final class SelectionListener implements IClaimListener {

    @Override
    public String describe() {
        return "the marking tool";
    }


    private final ClaimServices services;

    public SelectionListener(ClaimServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!services.stick().isStick(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);

        Selection selection = services.selections().selectionOrBegin(player);
        // A stick issued for resizing or a zone keeps that purpose even after a world change.
        selection.purpose(services.stick().purposeOf(event.getItem()));

        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK -> handleAddPoint(player, selection, event.getClickedBlock());
            case LEFT_CLICK_BLOCK -> handleUndo(player, selection);
            case RIGHT_CLICK_AIR -> {
                if (player.isSneaking()) {
                    services.selectionFlow().finish(player);
                } else {
                    services.screens().selection(player);
                }
            }
            case LEFT_CLICK_AIR -> {
                if (player.isSneaking()) {
                    services.selectionFlow().cancel(player);
                } else {
                    previewSelection(player, selection);
                }
            }
            default -> {
                // Physical interactions (pressure plates) are irrelevant here.
            }
        }
    }

    private void handleAddPoint(Player player, Selection selection, Block block) {
        if (block == null) {
            return;
        }
        if (!block.getWorld().getUID().equals(selection.worldId())) {
            services.messages().send(player, "selection.world-mismatch");
            return;
        }
        selection.addPoint(block.getX(), block.getY(), block.getZ());
        services.messages().send(player, "selection.point-added", 
                "index", String.valueOf(selection.pointCount()),
                "x", String.valueOf(block.getX()),
                "y", String.valueOf(block.getY()),
                "z", String.valueOf(block.getZ()));
        previewSelection(player, selection);

        boolean sneaking = player.isSneaking();
        // Rectangle mode has exactly two corners, so the second click is the whole selection: finish
        // right away and let the stick vanish, which is what the plan asks for.
        if (selection.mode() == Selection.Mode.RECTANGLE && selection.isComplete()) {
            services.selectionFlow().finish(player);
        } else if (sneaking && selection.isComplete()) {
            services.selectionFlow().finish(player);
        }
    }

    private void handleUndo(Player player, Selection selection) {
        if (selection.removeLastPoint()) {
            services.messages().send(player, "selection.point-removed",
                    "count", String.valueOf(selection.pointCount()));
            previewSelection(player, selection);
        } else {
            services.messages().send(player, "selection.nothing-to-undo");
        }
    }

    private void previewSelection(Player player, Selection selection) {
        // The glowing corner markers persist for the whole selection; the outline preview is temporary.
        services.visualizer().showSelectionMarkers(player, selection);
        int[] vertical = services.selections().resolveVerticalRange(selection);
        services.visualizer().showSelection(player, selection, vertical,
                services.config().visualDurationSeconds());
    }

    /** Dropping the stick is treated as "I am done here" so it never litters the world. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!services.stick().isStick(event.getItemDrop().getItemStack())) {
            return;
        }
        event.getItemDrop().remove();
        services.selectionFlow().cancel(event.getPlayer());
    }
}
