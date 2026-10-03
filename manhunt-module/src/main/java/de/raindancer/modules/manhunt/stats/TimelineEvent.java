package de.raindancer.modules.manhunt.stats;

import java.util.UUID;

/**
 * One thing that happened in a hunt, and when — milliseconds since it began.
 *
 * @param who    whom it happened to, or null for the hunt itself
 * @param other  the other party — the Hunter who made a catch, the Runner who killed a Hunter — or null
 * @param detail what the kind needs besides: a milestone's id, the lives left, the side joined
 */
public record TimelineEvent(long atMillis, Kind kind, UUID who, String whoName, UUID other, String otherName,
                            String detail) {

    public enum Kind {
        STARTED, CAUGHT, LIFE_LOST, CAUGHT_AWAY, HUNTER_DIED, LEFT, JOINED, SIDE_CHANGED, MILESTONE, FINISHED
    }
}
