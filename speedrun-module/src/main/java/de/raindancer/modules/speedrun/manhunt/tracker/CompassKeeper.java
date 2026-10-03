package de.raindancer.modules.speedrun.manhunt.tracker;

import org.bukkit.entity.Allay;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Keeps this module's compasses in their holder's own inventory.
 *
 * <h2>Why more than Core's bound items</h2>
 * {@code BoundItems} only refuses the drop. A compass put in a chest, an ender chest, a shulker box,
 * a bundle, an item frame, an armour stand or an allay's paws is out of its holder's inventory all the
 * same: the other side can take it, and its holder counts as missing one — which a respawn, a login
 * or {@code /manhunt give} would hand out again, a second compass for every one stashed. So whatever
 * would move one of ours anywhere but the holder's own inventory is refused, and it never drops from
 * a body. Inside their own inventory it moves freely.
 *
 * <p>Registered for the life of the module: the items outlive a hunt in an offline player's inventory.
 */
public final class CompassKeeper implements Listener {

    private final Predicate<ItemStack> ours;

    public CompassKeeper(Predicate<ItemStack> ours) {
        this.ours = Objects.requireNonNull(ours, "ours");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if ((ours(current) && isBundle(cursor)) || (ours(cursor) && isBundle(current))) {
            event.setCancelled(true);
            return;
        }
        if (!containerOpen(event.getView())) {
            return;
        }
        HumanEntity who = event.getWhoClicked();
        boolean hotkeyed = (event.getClick() == ClickType.NUMBER_KEY
                && ours(who.getInventory().getItem(event.getHotbarButton())))
                || (event.getClick() == ClickType.SWAP_OFFHAND && ours(who.getInventory().getItemInOffHand()));
        if (ours(current) || ours(cursor) || hotkeyed) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!ours(event.getOldCursor()) || !containerOpen(event.getView())) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntity(PlayerInteractEntityEvent event) {
        if ((event.getRightClicked() instanceof ItemFrame || event.getRightClicked() instanceof Allay)
                && ours(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmourStand(PlayerArmorStandManipulateEvent event) {
        if (ours(event.getPlayerItem())) {
            event.setCancelled(true);
        }
    }

    /** Never from a body — the player standing over a dead Hunter is usually the Runner who did it. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::ours);
    }

    private boolean ours(ItemStack stack) {
        return stack != null && ours.test(stack);
    }

    /** Every colour of bundle — by name, since the bundle tag needs a running server to resolve. */
    private static boolean isBundle(ItemStack stack) {
        return stack != null && stack.getType() != null && stack.getType().name().endsWith("BUNDLE");
    }

    /**
     * Anything on top but the player's own 2×2 crafting grid (four slots and the result) is somewhere
     * else. Asked by shape rather than by {@code InventoryType}, whose constants need a running
     * server; the creative screen is the same view to the server.
     */
    private static boolean containerOpen(InventoryView view) {
        Inventory top = view.getTopInventory();
        return !(top instanceof CraftingInventory && top.getSize() == 5);
    }
}
