package de.raindancer.modules.mannequin.claims;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.extension.ClaimMenuButton;
import de.raindancer.modules.claims.extension.ClaimMenuExtension;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.screen.ClaimMannequinsMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The button this module puts on {@code claims-module}'s own {@code ClaimMenu} — "Mannequins",
 * counted, opening {@link ClaimMannequinsMenu}. Registered by {@link ClaimIntegration} only, and only
 * when a real claims plugin is actually there to register it with.
 */
final class MannequinClaimMenuExtension implements ClaimMenuExtension {

    private final MannequinServices mannequins;

    MannequinClaimMenuExtension(MannequinServices mannequins) {
        this.mannequins = mannequins;
    }

    @Override
    public ClaimMenuButton contribute(ClaimServices services, Claim claim, Player viewer, Menu parent) {
        int count = mannequins.registry().inClaim(claim.id()).size();
        return new ClaimMenuButton(
                Icons.of(Material.ARMOR_STAND, "<white>Mannequins",
                        "<gray>Training dummies that belong to this claim.",
                        "<dark_gray>" + count + " right now"),
                click -> new ClaimMannequinsMenu(mannequins, viewer, claim.id(), claim.name(), parent).open());
    }
}
