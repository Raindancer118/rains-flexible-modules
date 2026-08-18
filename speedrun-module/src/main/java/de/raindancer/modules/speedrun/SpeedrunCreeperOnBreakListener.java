package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsStore;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * A run's own hazard: every block a participant breaks while the race is actually
 * {@link SpeedrunState#RUNNING} spawns a creeper right where it broke — occasionally a charged one, at
 * {@link SpeedrunSettings#chargedCreeperChanceOnBreakPercent()}, when
 * {@link SpeedrunSettings#creeperOnBlockBreak()} is on. See {@link SpeedrunCreeperOnContainerOpenListener}
 * for the same hazard on opening a container instead, with its own toggle and its own chance.
 *
 * <h2>Why session-scoped, not lobby-wide</h2>
 * Same reasoning as {@link SpeedrunOccupancyListener}: registered fresh in {@link SpeedrunLobby#start}
 * and unregistered with the session, so a block broken outside a run — tidying up the lobby before a
 * finished run's world resets, say — never spawns anything.
 *
 * <h2>Why participants only</h2>
 * Not every break in the world: an admin fixing the map, or a spectator, does not owe the racers a
 * creeper. Only a player {@link SpeedrunSession#participants()} names is charged for their own breaks.
 *
 * <h2>Why the block's own world, not a scheduler hop</h2>
 * {@link BlockBreakEvent} fires on the region thread that already owns the broken block's chunk, and
 * the creeper is spawned at that same block — there is no region boundary to cross, unlike
 * {@code SpeedrunReset}'s world-wide operations, which is why those need the global region scheduler
 * and this does not.
 */
public final class SpeedrunCreeperOnBreakListener implements Listener {

    private final SpeedrunSession session;
    private final SettingsStore<SpeedrunSettings> settings;

    public SpeedrunCreeperOnBreakListener(SpeedrunSession session, SettingsStore<SpeedrunSettings> settings) {
        this.session = session;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (session.state() != SpeedrunState.RUNNING) {
            return;
        }
        if (!session.participants().contains(event.getPlayer().getUniqueId())) {
            return;
        }
        SpeedrunSettings current = settings.current();
        if (!current.creeperOnBlockBreak()) {
            return;
        }
        Block block = event.getBlock();
        Location spawnAt = block.getLocation().add(0.5, 0, 0.5);
        SpeedrunCreeperHazard.spawn(spawnAt, current.chargedCreeperChanceOnBreakPercent());
    }
}
