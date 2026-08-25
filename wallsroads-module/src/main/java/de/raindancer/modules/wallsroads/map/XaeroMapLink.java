package de.raindancer.modules.wallsroads.map;

import de.raindancer.modules.xaeromap.XaeroMapServices;
import de.raindancer.modules.xaeromap.model.Waypoint;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** The real {@link MapLink}, once a client-map module is actually running. */
final class XaeroMapLink implements MapLink {

    private final XaeroMapServices map;

    XaeroMapLink(XaeroMapServices map) {
        this.map = map;
    }

    @Override
    public boolean available() {
        return map.waypoints().enabled();
    }

    @Override
    public int offer(Player player, List<Marker> markers) {
        if (!available() || !map.waypoints().canReceive(player)) {
            return 0;
        }
        List<Waypoint> waypoints = new ArrayList<>(markers.size());
        for (Marker marker : markers) {
            waypoints.add(new Waypoint(marker.kind() + ":" + marker.name(), marker.name(), marker.kind(),
                    player.getWorld().getKey().toString(),
                    marker.spot().x(), marker.spot().y(), marker.spot().z(),
                    "gate".equals(marker.kind()) ? NamedTextColor.GOLD : NamedTextColor.AQUA));
        }
        return map.waypoints().offer(player, waypoints);
    }
}
