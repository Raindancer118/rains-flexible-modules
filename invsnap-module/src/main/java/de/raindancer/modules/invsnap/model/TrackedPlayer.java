package de.raindancer.modules.invsnap.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One player, as the root browse screen needs to show them: who they are, how many snapshots are
 * kept, and when the newest one was taken. Built from a player's whole {@link Snapshot} history;
 * never touches the server itself — see {@code PackageGrammarTest}'s rule that the model stays
 * plain.
 */
public record TrackedPlayer(UUID id, String name, int count, Instant newest) {

    public TrackedPlayer {
        Objects.requireNonNull(id, "a tracked player needs an id");
        Objects.requireNonNull(newest, "a tracked player needs when its newest snapshot was taken");
        name = name == null || name.isBlank() ? id.toString() : name;
    }
}
