package de.raindancer.modules.anticheat.model;

/**
 * One movement the client reported: a packet when the tap is in, a {@code PlayerMoveEvent} otherwise.
 *
 * @param ticks     client ticks since the previous sample — 1 unless the client says otherwise
 * @param onGround  what the client claims, not what is true
 * @param fromPacket whether this came straight off the wire, so the claims are the client's own
 */
public record MoveSample(double x, double y, double z, float yaw, float pitch, boolean hasPosition,
                         boolean hasRotation, boolean onGround, boolean horizontalCollision, int ticks,
                         long nanos, boolean fromPacket) {
}
