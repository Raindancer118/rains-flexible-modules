package de.raindancer.modules.arena.model;

import de.raindancer.modules.arena.api.MatchSpec;

import java.util.UUID;

/**
 * Somebody waiting for a fight of a kind, since when.
 *
 * @param group the lasting group (guild) they fight for in a guild mode, or null
 */
public record QueueEntry(UUID player, MatchSpec spec, long since, String group) {

    public QueueEntry(UUID player, MatchSpec spec, long since) {
        this(player, spec, since, null);
    }
}
