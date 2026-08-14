package de.raindancer.modules.mannequin.claims;

import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * The real {@link ClaimLink}, backed by a live {@code ClaimServices} fetched from Bukkit's own
 * ServicesManager. Never constructed, and never even loaded as a class, unless {@link ClaimIntegration}
 * has already confirmed that service is actually there — see that class for why the split matters.
 */
final class MannequinClaimLink implements ClaimLink {

    private final ClaimServices claims;

    MannequinClaimLink(ClaimServices claims) {
        this.claims = claims;
    }

    @Override
    public Optional<UUID> claimAround(Player player) {
        return claims.claimAround(player).map(Claim::id);
    }

    @Override
    public boolean owns(UUID claimId, UUID player) {
        return claims.claims().byId(claimId).map(claim -> claim.isOwner(player)).orElse(false);
    }

    @Override
    public String nameOf(UUID claimId) {
        return claims.claims().byId(claimId).map(Claim::name).orElse(null);
    }
}
