package de.raindancer.modules.arena.listener;

import de.raindancer.modules.arena.util.Builders;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.model.ArenaAction;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;

import java.util.UUID;

/**
 * Nothing in the arena's worlds is changed or taken by anybody but somebody building (an admin in creative).
 *
 * <p>Two halves: the map stays as it was built, and no item can leave the arena — not by being dropped, not into a
 * chest, and above all not into an ender chest, which the second map has. No item entity exists in an arena world at
 * all: whatever would appear is stopped, which closes every way out at once that this list might have missed.
 */
public final class ProtectionListener implements IArenaListener {

    private final ArenaServices services;

    public ProtectionListener(ArenaServices services) {
        this.services = services;
    }

    private boolean arena(World world) {
        return world != null && services.arena().isArenaWorld(world.getName());
    }

    private void judge(Player player, ArenaAction action, Cancellable event) {
        if (player == null || !arena(player.getWorld())) {
            return;
        }
        if (!services.protection().allows(action, Builders.isBuilding(player))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        judge(event.getPlayer(), ArenaAction.BREAK, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        judge(event.getPlayer(), ArenaAction.PLACE, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        judge(event.getPlayer(), ArenaAction.BUCKET, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        judge(event.getPlayer(), ArenaAction.BUCKET, event);
    }

    /** Not ignoreCancelled: Bukkit hands right clicks on air in already cancelled, and those still use an item. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (event.getAction() == Action.PHYSICAL) {
            if (block.getType() == Material.FARMLAND) {
                judge(event.getPlayer(), ArenaAction.TRAMPLE, event);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !arena(block.getWorld())
                || Builders.isBuilding(event.getPlayer())) {
            return;
        }
        ArenaAction action = block.getType() == Material.ENDER_CHEST ? ArenaAction.ENDER_CHEST
                : block.getState(false) instanceof Container ? ArenaAction.CONTAINER
                : block.getType().isInteractable() ? ArenaAction.USE_BLOCK
                : null;
        if (action != null) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }
        // What the held item would do to the block — light it, grow it, strip it, spawn something on it — is
        // refused unless the item is something a fight uses whatever is behind the target.
        org.bukkit.inventory.ItemStack held = event.getItem();
        if (!services.itemUse().usableAgainstBlocks(held == null ? "AIR" : held.getType().name())) {
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    /** Boats, minecarts, armour stands, end crystals — things an item places that are entities, not blocks. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(org.bukkit.event.entity.EntityPlaceEvent event) {
        judge(event.getPlayer(), ArenaAction.PLACE, event);
        if (event.getPlayer() == null && arena(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(org.bukkit.event.hanging.HangingPlaceEvent event) {
        judge(event.getPlayer(), ArenaAction.PLACE, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleBreak(org.bukkit.event.vehicle.VehicleDestroyEvent event) {
        if (!arena(event.getVehicle().getWorld())) {
            return;
        }
        if (event.getAttacker() instanceof Player player) {
            judge(player, ArenaAction.BREAK, event);
        } else {
            event.setCancelled(true);
        }
    }

    /** Clicking an armour stand's slot arrives as this, not as the plain entity click. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityUseAt(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        onEntityUse(event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShear(org.bukkit.event.player.PlayerShearEntityEvent event) {
        judge(event.getPlayer(), ArenaAction.USE_BLOCK, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeash(org.bukkit.event.entity.PlayerLeashEntityEvent event) {
        judge(event.getPlayer(), ArenaAction.USE_BLOCK, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEntity(org.bukkit.event.player.PlayerBucketEntityEvent event) {
        judge(event.getPlayer(), ArenaAction.BUCKET, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(org.bukkit.event.block.SignChangeEvent event) {
        judge(event.getPlayer(), ArenaAction.PLACE, event);
    }

    // ── the world itself: the map stays as it was built ───────────────────────────────────────────

    /** Endermen, falling blocks, a mob trampling crops, a ravager breaking leaves — and a player only when building. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        if (!arena(event.getBlock().getWorld())) {
            return;
        }
        if (!(event.getEntity() instanceof Player player) || !Builders.isBuilding(player)) {
            event.setCancelled(true);
        }
    }

    /** A mob on farmland or a pressure plate it should not press. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(org.bukkit.event.entity.EntityInteractEvent event) {
        if (arena(event.getBlock().getWorld()) && event.getBlock().getType() == Material.FARMLAND) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(org.bukkit.event.block.BlockIgniteEvent event) {
        if (arena(event.getBlock().getWorld())
                && !(event.getPlayer() != null && Builders.isBuilding(event.getPlayer()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(org.bukkit.event.block.BlockBurnEvent event) {
        cancelInArena(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(org.bukkit.event.block.BlockSpreadEvent event) {
        cancelInArena(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFade(org.bukkit.event.block.BlockFadeEvent event) {
        cancelInArena(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(org.bukkit.event.block.BlockFormEvent event) {
        cancelInArena(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeavesDecay(org.bukkit.event.block.LeavesDecayEvent event) {
        cancelInArena(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(org.bukkit.event.block.BlockGrowEvent event) {
        cancelInArena(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(org.bukkit.event.block.BlockFertilizeEvent event) {
        cancelInArena(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStructureGrow(org.bukkit.event.world.StructureGrowEvent event) {
        if (arena(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    /** An arrow breaking a pot, lighting TNT or snapping dripstone — the hit stands, the block does not change. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        Block hit = event.getHitBlock();
        if (hit == null || !arena(hit.getWorld())) {
            return;
        }
        switch (hit.getType()) {
            case DECORATED_POT, CHORUS_FLOWER, POINTED_DRIPSTONE, TNT, BELL, TARGET -> event.setCancelled(true);
            default -> {
                if (hit.getType().name().endsWith("CANDLE") || hit.getType().name().endsWith("CAMPFIRE")) {
                    event.setCancelled(true);
                }
            }
        }
    }

    private void cancelInArena(Block block, Cancellable event) {
        if (arena(block.getWorld())) {
            event.setCancelled(true);
        }
    }

    /** Whatever opens a container some other way — a command, a plugin, a minecart — is caught here. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.ENDER_CHEST) {
            judge(player, ArenaAction.ENDER_CHEST, event);
        } else if (type != InventoryType.CRAFTING && type != InventoryType.PLAYER
                && event.getInventory().getHolder() != null
                && !(event.getInventory().getHolder() instanceof Player)
                && !(event.getInventory().getHolder() instanceof de.raindancer.core.ui.menu.Menu)) {
            judge(player, ArenaAction.CONTAINER, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        judge(event.getPlayer(), ArenaAction.DROP, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickUp(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            judge(player, ArenaAction.PICK_UP, event);
        } else if (arena(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityUse(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (clicked instanceof Hanging) {
            judge(event.getPlayer(), ArenaAction.FRAME, event);
        } else if (clicked instanceof ArmorStand) {
            judge(event.getPlayer(), ArenaAction.ARMOUR_STAND, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmourStand(PlayerArmorStandManipulateEvent event) {
        judge(event.getPlayer(), ArenaAction.ARMOUR_STAND, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player) {
            judge(player, ArenaAction.FRAME, event);
        } else if (arena(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    /** Hitting a frame or a stand is breaking it — Combat leaves things that are not alive to whoever owns them. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHitThing(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && (event.getEntity() instanceof Hanging || event.getEntity() instanceof ArmorStand)) {
            judge(player, ArenaAction.FRAME, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (arena(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (arena(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    /** A wither or a dragon is not let in, however it was made — /summon included. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntitySpawn(org.bukkit.event.entity.EntitySpawnEvent event) {
        if (arena(event.getLocation().getWorld()) && !services.protection().mayExist(event.getEntityType().name())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (arena(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (arena(event.getFrom().getWorld()) && !Builders.isBuilding(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered: every answer is worked out from where somebody is at the moment they act.
    }
}
