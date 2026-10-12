package de.raindancer.modules.guilds.model;

import java.util.UUID;

/**
 * An open question between a guild and a player, or between two guilds.
 *
 * @param kind    what is being asked
 * @param guild   the guild asking (an invitation, an alliance) or being asked (an application)
 * @param subject the invited or applying player, or for an alliance the other guild's id as a name-based UUID
 * @param by      who asked
 * @param other   for an alliance, the id value of the guild being asked; otherwise empty
 */
public record Invitation(Kind kind, String guild, UUID subject, UUID by, String other, long expires) {

    public enum Kind { INVITE, APPLICATION, ALLIANCE }

    public Invitation {
        other = other == null ? "" : other;
    }

    public boolean isExpiredAt(long now) {
        return now >= expires;
    }
}
