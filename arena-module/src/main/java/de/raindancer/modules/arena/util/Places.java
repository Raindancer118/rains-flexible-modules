package de.raindancer.modules.arena.util;

import de.raindancer.modules.arena.model.Place;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;

import java.util.Optional;

/** Between the module's {@link Place} and Bukkit's {@link Location}. */
public final class Places {

    private Places() {
    }

    public static Place of(Location location) {
        return new Place(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    /** Empty when the world is not loaded — a place in a world that is gone is nowhere anybody can be put. */
    public static Optional<Location> location(Server server, Place place) {
        World world = server.getWorld(place.world());
        return world == null ? Optional.empty()
                : Optional.of(new Location(world, place.x(), place.y(), place.z(), place.yaw(), place.pitch()));
    }
}
