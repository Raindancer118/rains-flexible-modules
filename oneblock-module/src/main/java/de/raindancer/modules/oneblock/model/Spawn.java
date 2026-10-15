package de.raindancer.modules.oneblock.model;

/** Where somebody arrives on an island, looking which way — the one-block spot's top until the team sets one. */
public record Spawn(double x, double y, double z, float yaw, float pitch) {

    public static Spawn above(BlockSpot spot) {
        return new Spawn(spot.x() + 0.5, spot.y() + 1.0, spot.z() + 0.5, 0f, 0f);
    }
}
