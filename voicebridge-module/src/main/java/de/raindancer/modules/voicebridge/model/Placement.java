package de.raindancer.modules.voicebridge.model;

import java.util.UUID;

/** Where a voice comes from, as Simple Voice Chat described it in the packet. */
public sealed interface Placement {

    /** A group voice or a plugin's static channel: in your head, both ears. */
    record Static() implements Placement {
    }

    /** A fixed point in the world, audible up to {@code distance} blocks. */
    record At(double x, double y, double z, double distance) implements Placement {
    }

    /** Wherever this entity is at the moment it is heard. */
    record Following(UUID entity, double distance) implements Placement {
    }
}
