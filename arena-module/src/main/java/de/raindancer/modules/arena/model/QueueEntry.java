package de.raindancer.modules.arena.model;

import de.raindancer.modules.arena.api.MatchSpec;
import java.util.UUID;

/** Somebody waiting for a fight of a kind, since when. */
public record QueueEntry(UUID player, MatchSpec spec, long since) {
}
