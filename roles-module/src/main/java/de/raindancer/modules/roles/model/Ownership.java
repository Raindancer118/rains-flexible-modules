package de.raindancer.modules.roles.model;

import java.util.UUID;

/**
 * A role a player paid for.
 *
 * @param dueAt when the next rent is due, epoch millis; zero for a role bought outright
 */
public record Ownership(UUID player, String role, Kind kind, long dueAt) {

    public enum Kind { BOUGHT, RENTED }
}
