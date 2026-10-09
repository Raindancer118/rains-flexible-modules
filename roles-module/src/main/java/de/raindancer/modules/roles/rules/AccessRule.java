package de.raindancer.modules.roles.rules;

import de.raindancer.modules.roles.model.Ownership;
import de.raindancer.modules.roles.model.Role;

import java.util.Optional;

/** Whether somebody may take a role: it is free, they paid for it, or they are staff skipping the price. */
public final class AccessRule implements IRolesRule {

    public boolean may(Role role, Optional<Ownership> owned, boolean bypass) {
        return bypass || !role.forSale() || owned.isPresent();
    }

    @Override
    public String describe() {
        return "whether a role is open to a player, or has to be bought or rented first";
    }
}
