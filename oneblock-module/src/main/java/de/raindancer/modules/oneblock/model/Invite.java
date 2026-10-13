package de.raindancer.modules.oneblock.model;

/** One open invite, held on the island server until it is answered or runs out at {@code until} (epoch millis). */
public record Invite(InviteKind kind, Requester from, Requester to, String islandId, long until) {

    public boolean ranOut(long now) {
        return now > until;
    }
}
