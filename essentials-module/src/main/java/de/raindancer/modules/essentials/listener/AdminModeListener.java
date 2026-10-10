package de.raindancer.modules.essentials.listener;

import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.rules.AdminKeepApartRule;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.block.Container;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.UUID;

/**
 * Admin items stay on the admin side: dying drops nothing, no advancements are made, and — unless the owner
 * switched it off — nothing is dropped, picked up, stored, traded or put on display. Windows that keep items can
 * still be looked into; with "use containers" on, they and frames, stands and shelves can be used, each change
 * written to the audit log.
 */
public final class AdminModeListener implements IEssentialsListener {

    private final EssentialsServices services;
    private final AdminKeepApartRule rule = new AdminKeepApartRule();

    public AdminModeListener(EssentialsServices services) {
        this.services = services;
    }

    private boolean apart(Player player) {
        return services.adminMode().keepsItemsApart(player.getUniqueId());
    }

    private boolean containers(Player player) {
        return services.adminMode().usesContainers(player.getUniqueId());
    }

    /** What a container held when somebody in admin mode opened it, by the inventory, until they close it. */
    private final java.util.Map<UUID, Opened> opened = new java.util.concurrent.ConcurrentHashMap<>();

    private record Opened(Inventory inventory, java.util.Map<String, Integer> counts) {
    }

    /**
     * What a container holds, by item — the whole item, not only its kind: a plain diamond sword taken out for an
     * enchanted one put in is a change, and must read as one.
     */
    private static java.util.Map<String, Integer> counts(Inventory inventory) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                counts.merge(key(stack), stack.getAmount(), Integer::sum);
            }
        }
        return counts;
    }

    /** An item's kind, and — when it is more than plain — what makes it so and a short fingerprint of exactly what. */
    private static String key(ItemStack stack) {
        ItemStack one = stack.asOne();
        if (!one.hasItemMeta() || one.isSimilar(new ItemStack(one.getType()))) {
            return one.getType().name();
        }
        var meta = one.getItemMeta();
        java.util.List<String> marks = new java.util.ArrayList<>();
        if (meta.hasEnchants() || meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta stored && stored.hasStoredEnchants()) {
            marks.add("enchanted");
        }
        if (meta.hasCustomName()) {
            marks.add("named");
        }
        if (marks.isEmpty()) {
            marks.add("changed");
        }
        return one.getType().name() + " (" + String.join(", ", marks) + " #"
                + Integer.toHexString(java.util.Arrays.hashCode(one.serializeAsBytes())) + ")";
    }

    private void audit(Player player, String action, org.bukkit.Location where, String detail) {
        var entry = de.raindancer.core.moderation.audit.AuditEntry.of("essentials", action)
                .by(player.getUniqueId(), player.getName()).saying(detail);
        if (where != null && where.getWorld() != null) {
            entry.in(where.getWorld().getName()).with("where",
                    where.getBlockX() + " " + where.getBlockY() + " " + where.getBlockZ());
        }
        services.core().audit().record(entry);
    }

    /** No advancements are made in admin mode: nothing done there is the player's own play. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAdvancement(com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent event) {
        if (services.adminMode().isInAdminMode(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!services.adminMode().isInAdminMode(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (apart(event.getPlayer())) {
            event.setCancelled(true);
            services.messages().send(event.getPlayer(), "essentials.admin.kept-apart");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && apart(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !apart(player)) {
            return;
        }
        if (closed(event.getView(), player)) {
            services.messages().send(player, "essentials.admin.look-only");
        } else if (containers(player) && keeps(event.getView())) {
            opened.put(player.getUniqueId(), new Opened(event.getView().getTopInventory(),
                    counts(event.getView().getTopInventory())));
        }
    }

    /** The audit line for a container somebody in admin mode changed. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCloseContainer(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            written(player);
        }
    }

    /** Leaving with a container open writes its line too. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuitWithContainer(org.bukkit.event.player.PlayerQuitEvent event) {
        written(event.getPlayer());
    }

    /** Writes the line for the container this player had open in admin mode, if anything in it changed. */
    private void written(Player player) {
        Opened was = opened.remove(player.getUniqueId());
        if (was == null) {
            return;
        }
        AdminKeepApartRule.Changes changes = rule.changes(was.counts(), counts(was.inventory()));
        if (changes.none()) {
            return;
        }
        String detail = (changes.in().isEmpty() ? "" : "put in " + changes.says(changes.in()))
                + (changes.in().isEmpty() || changes.out().isEmpty() ? "" : "; ")
                + (changes.out().isEmpty() ? "" : "took out " + changes.says(changes.out()));
        audit(player, "used a " + was.inventory().getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                + " in admin mode", was.inventory().getLocation(), detail);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && apart(player) && closed(event.getView(), player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && apart(player) && closed(event.getView(), player)) {
            event.setCancelled(true);
        }
    }

    /** A window this player may not use — never one of our own menus, which handle their clicks. */
    private boolean closed(InventoryView view, Player player) {
        Inventory top = view.getTopInventory();
        var holder = top.getHolder(false);
        if (holder instanceof Menu) {
            return false;
        }
        boolean own = holder == null ? !"PLAYER".equals(top.getType().name()) && !"ENDER_CHEST".equals(top.getType().name())
                : holder.equals(player);
        if ("ENDER_CHEST".equals(top.getType().name()) && holder == null) {
            // The ender chest block opens the viewer's own; it is held by the player, so a null holder is a window
            // somebody built — not theirs.
            own = false;
        }
        boolean inWorld = holder instanceof org.bukkit.block.BlockState || holder instanceof org.bukkit.block.DoubleChest
                || holder instanceof org.bukkit.entity.Entity && !(holder instanceof Player);
        return !rule.mayUse(top.getType().name(), containers(player), own, inWorld);
    }

    /** A window that keeps what is put in it, in the world — the ones worth a line in the audit log. */
    private boolean keeps(InventoryView view) {
        Inventory top = view.getTopInventory();
        return !(top.getHolder(false) instanceof Menu) && !rule.mayUseWindow(top.getType().name());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || !apart(event.getPlayer())) {
            return;
        }
        if (!rule.mayUseBlock(event.getClickedBlock().getType().name())) {
            if (containers(event.getPlayer())) {
                ItemStack held = event.getItem();
                if (held != null && !held.getType().isAir()) {
                    audit(event.getPlayer(), "used a " + de.raindancer.core.ui.choose.Catalogue.readable(
                            event.getClickedBlock().getType().name()) + " in admin mode", event.getClickedBlock().getLocation(),
                            "holding " + held.getAmount() + " " + de.raindancer.core.ui.choose.Catalogue.readable(held.getType().name()));
                }
                return;
            }
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            services.messages().send(event.getPlayer(), "essentials.admin.kept-apart");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityUse(PlayerInteractEntityEvent event) {
        if ((event.getRightClicked() instanceof ItemFrame || event.getRightClicked() instanceof Allay)
                && apart(event.getPlayer())) {
            if (containers(event.getPlayer())) {
                ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
                audit(event.getPlayer(), "used " + (event.getRightClicked() instanceof Allay ? "an allay" : "an item frame")
                        + " in admin mode", event.getRightClicked().getLocation(), held.getType().isAir() ? "empty-handed"
                        : "holding " + held.getAmount() + " " + de.raindancer.core.ui.choose.Catalogue.readable(held.getType().name()));
                return;
            }
            event.setCancelled(true);
            services.messages().send(event.getPlayer(), "essentials.admin.kept-apart");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (apart(event.getPlayer())) {
            if (containers(event.getPlayer())) {
                audit(event.getPlayer(), "used an armour stand in admin mode", event.getRightClicked().getLocation(),
                        "gave " + event.getPlayerItem().getType().name().toLowerCase(java.util.Locale.ROOT)
                                + ", took " + event.getArmorStandItem().getType().name().toLowerCase(java.util.Locale.ROOT));
                return;
            }
            event.setCancelled(true);
            services.messages().send(event.getPlayer(), "essentials.admin.kept-apart");
        }
    }

    /** A shulker box (or any container) placed full would leave its contents in the world. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack placed = event.getItemInHand();
        if (apart(event.getPlayer()) && placed.getItemMeta() instanceof BlockStateMeta meta && meta.hasBlockState()
                && meta.getBlockState() instanceof Container container && !container.getInventory().isEmpty()) {
            if (containers(event.getPlayer())) {
                audit(event.getPlayer(), "placed a filled " + de.raindancer.core.ui.choose.Catalogue.readable(placed.getType().name())
                        + " in admin mode", event.getBlockPlaced().getLocation(), "holding "
                        + rule.changes(java.util.Map.of(), counts(container.getInventory())).says(counts(container.getInventory())));
                return;
            }
            event.setCancelled(true);
            services.messages().send(event.getPlayer(), "essentials.admin.kept-apart");
        }
    }

    @Override
    public void forget(UUID player) {
        opened.remove(player);
    }
}
