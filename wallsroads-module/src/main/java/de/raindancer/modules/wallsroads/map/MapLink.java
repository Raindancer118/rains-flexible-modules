package de.raindancer.modules.wallsroads.map;

import de.raindancer.core.world.safety.Spot;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * What this module can ask a client-map module for. {@link #NONE} on a server without one.
 *
 * <p>Points only, deliberately: the client protocol carries waypoints, and a road is a line and a
 * wall a polygon. Rather than pretend either is a claim so it can be drawn as one, what goes on the
 * map is what genuinely is a point — where a road starts and ends, and where its gates are, which is
 * what somebody navigating actually steers for.
 */
public interface MapLink {

    MapLink NONE = new MapLink() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public int offer(Player player, List<Marker> markers) {
            return 0;
        }
    };

    /** One place worth steering for. */
    record Marker(String name, String kind, Spot spot) {
    }

    boolean available();

    /** @return how many the player's client was actually offered */
    int offer(Player player, List<Marker> markers);
}
