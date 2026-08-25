package de.raindancer.modules.wallsroads.listener;

import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Walking a built road is faster than walking beside it.
 *
 * <h2>Why the bonus, and why it is small</h2>
 * A road network that is only decoration gets built once and never extended. A road that is
 * genuinely the quicker way between two places is a thing a server keeps building, and the reason
 * anybody links their town to the next one. Small, because a road that outruns a horse makes the
 * horse pointless.
 *
 * <p>Only on a block change, never on every move event: a move event fires several times a second
 * per player, and a registry lookup on each is the kind of thing that quietly costs a server its
 * tick rate.
 */
public final class RoadTravelListener implements IWallsRoadsListener {

    /** Long enough to survive the gap between two block changes, short enough to end when they step off. */
    private static final int EFFECT_TICKS = 60;

    private final WallsRoadsServices services;

    public RoadTravelListener(WallsRoadsServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!services.config().roadSpeedBonus()) {
            return;
        }
        if (event.getTo().getBlockX() == event.getFrom().getBlockX()
                && event.getTo().getBlockY() == event.getFrom().getBlockY()
                && event.getTo().getBlockZ() == event.getFrom().getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        Spot underfoot = new Spot(player.getWorld().getName(), event.getTo().getBlockX(),
                event.getTo().getBlockY() - 1, event.getTo().getBlockZ());

        boolean onARoad = services.service().occupancy().ownerOf(underfoot)
                .flatMap(id -> services.registry().road(id))
                .isPresent();
        if (!onARoad) {
            return;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, EFFECT_TICKS, 0, true, false, false));
    }
}
