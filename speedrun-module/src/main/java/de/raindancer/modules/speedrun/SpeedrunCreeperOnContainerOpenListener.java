package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsStore;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * The same hazard as {@link SpeedrunCreeperOnBreakListener}, one trigger over: a participant opening a
 * chest or other container during a run spawns a creeper right where it stands with probability
 * {@link SpeedrunSettings#creeperSpawnChanceOnContainerPercent()} — occasionally a charged one, at
 * {@link SpeedrunSettings#chargedCreeperChanceOnContainerPercent()}.
 *
 * <h2>Why its own pair of chances</h2>
 * Opening a loot chest is a very different risk from mining — a server owner may want one hazard turned
 * down and not the other, or a gentler charged-creeper chance on chests than on ordinary block breaks.
 * Both settings are asked separately rather than sharing {@link SpeedrunCreeperOnBreakListener}'s.
 *
 * <h2>Why a container's location, not the event's</h2>
 * {@link InventoryOpenEvent} carries no location of its own — it is about an inventory, which may
 * belong to nothing in the world at all (a crafting grid, a villager trade menu). Only when the
 * inventory's holder is an actual placed {@link Container} — or one half of a {@link DoubleChest} — is
 * there a block to spawn beside; every other holder is left alone.
 */
public final class SpeedrunCreeperOnContainerOpenListener implements Listener {

    private final SpeedrunSession session;
    private final SettingsStore<SpeedrunSettings> settings;

    public SpeedrunCreeperOnContainerOpenListener(SpeedrunSession session, SettingsStore<SpeedrunSettings> settings) {
        this.session = session;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (session.state() != SpeedrunState.RUNNING) {
            return;
        }
        HumanEntity opener = event.getPlayer();
        if (!session.participants().contains(opener.getUniqueId())) {
            return;
        }
        SpeedrunSettings current = settings.current();
        Location spawnAt = locationOf(event.getInventory().getHolder());
        if (spawnAt == null) {
            return;
        }
        SpeedrunCreeperHazard.maybeSpawn(spawnAt.add(0.5, 0, 0.5), current.creeperSpawnChanceOnContainerPercent(),
                current.chargedCreeperChanceOnContainerPercent());
    }

    /** Where to spawn, for the holders that are actually a placed block; {@code null} for anything else. */
    private static Location locationOf(InventoryHolder holder) {
        if (holder instanceof Container container) {
            return container.getLocation();
        }
        if (holder instanceof DoubleChest doubleChest) {
            return doubleChest.getLocation();
        }
        return null;
    }
}
