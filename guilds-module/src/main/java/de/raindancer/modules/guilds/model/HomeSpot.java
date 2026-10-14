package de.raindancer.modules.guilds.model;

import java.util.Objects;

/**
 * Where the guild home is, facing the way it was set.
 *
 * @param server the server whose world it is in; blank for a server of its own, where every world is here
 */
public record HomeSpot(String world, double x, double y, double z, float yaw, float pitch, String server) {

    public HomeSpot {
        Objects.requireNonNull(world, "world");
        server = server == null ? "" : server;
    }

    public HomeSpot(String world, double x, double y, double z, float yaw, float pitch) {
        this(world, x, y, z, yaw, pitch, "");
    }

    /** Whether the home is in a world of {@code self}: a home with no server is everybody's own. */
    public boolean isOn(String self) {
        return server.isBlank() || self == null || self.isBlank() || server.equals(self);
    }

    public String describe() {
        return world + " " + (int) Math.floor(x) + " " + (int) Math.floor(y) + " " + (int) Math.floor(z);
    }
}
