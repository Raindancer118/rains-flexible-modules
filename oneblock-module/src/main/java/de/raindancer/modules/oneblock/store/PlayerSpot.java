package de.raindancer.modules.oneblock.store;

/** Where somebody was on the island server when they left, by world name — the world may be unloaded by their return. */
public record PlayerSpot(String world, double x, double y, double z, float yaw, float pitch) {
}
