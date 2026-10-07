package de.raindancer.modules.anticheat.listener;

import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.anticheat.AntiCheatServices;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Flag;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Deque;
import java.util.Locale;

/** Containers and clicks: walking with a screen open, inhumanly fast clicking, instant totems. */
public final class InventoryListener implements IAntiCheatListener {

    private final AntiCheatServices services;

    public InventoryListener(AntiCheatServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && event.getInventory().getType() != InventoryType.CRAFTING) {
            PlayerTrack track = services.tracks().of(player);
            synchronized (track) {
                track.world.containerOpenedMillis = track.now();
                track.world.containerClicks.clear();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PlayerTrack track = services.tracks().of(player);
            synchronized (track) {
                track.world.containerOpenedMillis = 0;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE
                || event.getInventory().getHolder() instanceof Menu) {
            return;
        }
        PlayerTrack track = services.tracks().of(player);
        long now = track.now();
        String walking = null;
        String fast = null;
        String totem = null;
        synchronized (track) {
            PlayerTrack.Movement m = track.movement;
            track.combat.lastInventoryClickMillis = now;
            boolean screenSure = now - m.inputChangedMillis > 300L + track.ping;
            if (m.inputSeen && m.moving() && screenSure && !Double.isNaN(m.lastHd) && m.lastHd > 0.15
                    && !player.isGliding() && !player.isInsideVehicle() && !player.isFlying() && !m.special) {
                walking = String.format(Locale.ROOT, "clicked slot %d while walking at %.2f", event.getRawSlot(), m.lastHd);
            }
            if (event.getClick() != ClickType.DOUBLE_CLICK) {
                Deque<Long> clicks = track.world.containerClicks;
                clicks.addLast(now);
                while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000) {
                    clicks.removeFirst();
                }
                int sameTick = 0;
                for (long at : clicks) {
                    if (now - at <= 50) {
                        sameTick++;
                    }
                }
                if (clicks.size() > 25 || sameTick >= 6) {
                    fast = clicks.size() + " clicks in a second, " + sameTick + " in one tick";
                    clicks.clear();
                }
            }
            ItemStack moved = event.getCursor();
            boolean offhandSlot = event.getSlotType() == InventoryType.SlotType.QUICKBAR && event.getSlot() == 40
                    || event.getRawSlot() == 45 && event.getView().getType() == InventoryType.CRAFTING;
            if (offhandSlot && moved != null && moved.getType() == Material.TOTEM_OF_UNDYING
                    && track.combat.totemPoppedMillis > 0) {
                long after = now - track.combat.totemPoppedMillis - track.ping;
                track.combat.totemPoppedMillis = 0;
                if (after < 150) {
                    totem = "a new totem in the off hand " + Math.max(0, after) + " ms after one popped";
                }
            }
        }
        if (walking != null && services.violations().runs(track, CheckType.INVENTORY_MOVE)
                && track.buffer(CheckType.INVENTORY_MOVE, 1, 0.2).fail(1)) {
            if (services.violations().flag(player, track, Flag.of(CheckType.INVENTORY_MOVE, walking)).act()) {
                event.setCancelled(true);
            }
        }
        if (fast != null && services.violations().runs(track, CheckType.FAST_CLICK)) {
            services.violations().flag(player, track, Flag.of(CheckType.FAST_CLICK, fast));
        }
        if (totem != null && services.violations().runs(track, CheckType.AUTO_TOTEM)) {
            services.violations().flag(player, track, Flag.of(CheckType.AUTO_TOTEM, totem));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        PlayerTrack track = services.tracks().of(event.getPlayer());
        String totem = null;
        synchronized (track) {
            ItemStack offhand = event.getOffHandItem();
            if (offhand != null && offhand.getType() == Material.TOTEM_OF_UNDYING && track.combat.totemPoppedMillis > 0) {
                long after = track.now() - track.combat.totemPoppedMillis - track.ping;
                track.combat.totemPoppedMillis = 0;
                if (after < 80) {
                    totem = "swapped a totem into the off hand " + Math.max(0, after) + " ms after one popped";
                }
            }
        }
        if (totem != null && services.violations().runs(track, CheckType.AUTO_TOTEM)) {
            services.violations().flag(event.getPlayer(), track, Flag.of(CheckType.AUTO_TOTEM, totem));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            PlayerTrack track = services.tracks().of(player);
            synchronized (track) {
                track.combat.totemPoppedMillis = track.now();
            }
        }
    }

    @Override
    public String describe() {
        return "judging containers and inventory clicks";
    }
}
