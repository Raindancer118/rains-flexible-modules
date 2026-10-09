package de.raindancer.modules.roles.model;

import java.util.UUID;

/**
 * The role a player took.
 *
 * @param chosenAt  when the wait before the next change started — staff can move it to end a wait
 * @param heldSince when they took this role; its perks grow from here
 */
public record Choice(UUID player, String role, long chosenAt, long heldSince) {

    public Choice(UUID player, String role, long chosenAt) {
        this(player, role, chosenAt, chosenAt);
    }
}
