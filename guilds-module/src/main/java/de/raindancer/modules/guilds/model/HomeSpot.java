package de.raindancer.modules.guilds.model;

import java.util.Objects;

/** Where the guild home is, facing the way it was set. */
public record HomeSpot(String world, double x, double y, double z, float yaw, float pitch) {

    public HomeSpot {
        Objects.requireNonNull(world, "world");
    }

    public String describe() {
        return world + " " + (int) Math.floor(x) + " " + (int) Math.floor(y) + " " + (int) Math.floor(z);
    }
}
