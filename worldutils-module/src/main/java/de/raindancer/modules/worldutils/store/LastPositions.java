package de.raindancer.modules.worldutils.store;

import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.poi.PoiStore;
import org.bukkit.Location;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Where each player last stood in each world — so {@code /w farm} puts them back where they left the
 * farm, not at its spawn.
 *
 * <h2>Why Core's place store</h2>
 * A remembered position is a place a plugin asked to remember, which is exactly what Core's
 * {@link PoiStore} is: one table, written on Core's timer rather than on the thread somebody is
 * teleporting on, and one set of answers for a world that is gone or a file that is half-written. The
 * {@link #KIND} keeps these out of anything that lists homes or warps.
 */
public final class LastPositions {

    public static final String KIND = "worldutils-last-position";

    private final PoiStore places;

    public LastPositions(PoiStore places) {
        this.places = places;
    }

    /** Remembers where {@code player} is standing now, as their place in that world. */
    public void remember(UUID player, Location where) {
        if (player == null || where == null || !where.isWorldLoaded() || where.getWorld() == null) {
            return;
        }
        String world = where.getWorld().getName();
        places.save(Poi.Builder.at(world, where)
                .id(idOf(player, world))
                .kind(KIND)
                .owner(player)
                .build());
    }

    /** Where {@code player} last stood in {@code world}, while that world is loaded. */
    public Optional<Location> in(UUID player, String world) {
        if (player == null || world == null) {
            return Optional.empty();
        }
        return places.byId(idOf(player, world)).flatMap(Poi::location);
    }

    /**
     * Forgets every position in a world — after it was regenerated or deleted, when those coordinates are
     * no longer anywhere anybody stood.
     *
     * @return how many were forgotten
     */
    public int forgetWorld(String world) {
        if (world == null) {
            return 0;
        }
        int forgotten = 0;
        for (Poi place : places.ofKind(KIND)) {
            if (place.world().equalsIgnoreCase(world) && places.delete(place.id())) {
                forgotten++;
            }
        }
        return forgotten;
    }

    private static String idOf(UUID player, String world) {
        return KIND + ":" + player + ":" + world.toLowerCase(Locale.ROOT);
    }
}
