package de.raindancer.modules.arena.model;

/** A position somebody can be put at, and which way they face. */
public record Place(String world, double x, double y, double z, float yaw, float pitch) {

    public Place {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("a place needs a world");
        }
    }

    public int blockY() {
        return (int) Math.floor(y);
    }
}
