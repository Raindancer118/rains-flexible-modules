package de.raindancer.modules.xaeromap.store;

import de.raindancer.core.world.poi.Poi;

import java.util.List;
import java.util.UUID;

/**
 * The two questions this module asks about places, and nothing else.
 *
 * <p>Homes and warps are both {@code Poi}s in RainsCore's own store — a home is a place of kind
 * {@code home}, a warp a place of kind {@code warp} — so putting them on a map needs no dependency on
 * {@code homes-module} or {@code warp-module} at all, and works the same for any other plugin that
 * files its places there.
 *
 * <p>An interface over two methods of {@code PoiStore} rather than the store itself, so the waypoint
 * service can be tested against a handful of places instead of a database.
 */
public interface PlaceLookup {

    /** Nothing at all, for a server whose Core has no places yet. */
    PlaceLookup NONE = new PlaceLookup() {

        @Override
        public List<Poi> ofKind(String kind) {
            return List.of();
        }

        @Override
        public List<Poi> owned(UUID owner, String kind) {
            return List.of();
        }
    };

    /** Every place of that kind, whoever owns it. */
    List<Poi> ofKind(String kind);

    /** That player's own places of that kind. */
    List<Poi> owned(UUID owner, String kind);
}
