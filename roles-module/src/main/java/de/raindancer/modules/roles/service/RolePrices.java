package de.raindancer.modules.roles.service;

import de.raindancer.core.social.economy.PriceChange;
import de.raindancer.core.social.economy.PriceModifier;
import de.raindancer.core.social.economy.TradeSide;
import de.raindancer.modules.roles.RolesSettings;
import de.raindancer.modules.roles.rules.PerkRule;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A role's perks, handed to Core as a price change — so the shop gives a Cook their discount without
 * knowing roles exist, and any other shop asking Core's {@code PriceModifiers} does the same.
 */
public final class RolePrices implements PriceModifier {

    private final RoleService roles;
    private final Supplier<RolesSettings> settings;
    private final PerkRule rule = new PerkRule();

    public RolePrices(RoleService roles, Supplier<RolesSettings> settings) {
        this.roles = roles;
        this.settings = settings;
    }

    @Override
    public Optional<PriceChange> change(UUID player, String material, TradeSide side) {
        if (!settings.get().perks()) {
            return Optional.empty();
        }
        return roles.roleOf(player).flatMap(role -> rule.change(role, material, side));
    }
}
