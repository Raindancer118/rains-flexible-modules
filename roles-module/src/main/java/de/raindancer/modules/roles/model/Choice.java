package de.raindancer.modules.roles.model;

import java.util.UUID;

/** The role a player took, and when — the wait before the next change runs from {@code chosenAt}. */
public record Choice(UUID player, String role, long chosenAt) {
}
