package de.raindancer.modules.roles.rules;

import de.raindancer.core.social.economy.PriceChange;
import de.raindancer.core.social.economy.TradeSide;
import de.raindancer.modules.roles.model.Perk;
import de.raindancer.modules.roles.model.Role;

import java.util.Comparator;
import java.util.Optional;

/**
 * What a role changes about one item's price. Where two of a role's perks cover the same item the better
 * one counts; they do not add up, so a role's description is never outdone by an overlap nobody wrote down.
 */
public final class PerkRule implements IRolesRule {

    public Optional<PriceChange> change(Role role, String material, TradeSide side) {
        if (role == null || material == null) {
            return Optional.empty();
        }
        return role.perks().stream()
                .filter(perk -> perk.side() == side && perk.percent() != 0 && perk.covers(material))
                .max(Comparator.comparingInt(perk -> side == TradeSide.BUY ? -perk.percent() : perk.percent()))
                .map(Perk::percent)
                .map(percent -> new PriceChange(percent, role.title()));
    }

    @Override
    public String describe() {
        return "what a role changes about one item's price, the best of its perks";
    }
}
