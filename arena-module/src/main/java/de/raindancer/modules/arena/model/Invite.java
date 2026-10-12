package de.raindancer.modules.arena.model;

import java.util.UUID;

/**
 * One player asking another to a fight.
 *
 * @param id      short, so it fits in a click
 * @param expires when it can no longer be accepted, epoch millis
 */
public record Invite(String id, UUID from, String fromName, UUID to, String toName, MatchSpec spec, long expires) {

    public boolean isExpired(long now) {
        return now >= expires;
    }
}
