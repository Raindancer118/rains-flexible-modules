package de.raindancer.modules.roles.service;

import de.raindancer.modules.roles.model.Role;

import java.util.UUID;

/** Whether a player may take a role now — the one question {@link RoleService} asks the shop. */
public interface RoleAccess {

    /** Everything open: what a server without prices has always had. */
    RoleAccess OPEN = (player, role) -> true;

    boolean may(UUID player, Role role);
}
