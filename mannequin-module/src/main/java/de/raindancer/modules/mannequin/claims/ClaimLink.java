package de.raindancer.modules.mannequin.claims;

import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Everything the rest of this module ever needs to know about claims — which is deliberately almost
 * nothing, and none of it a {@code Claim} or {@code ClaimServices} type.
 *
 * <h2>Why this exists instead of the real thing being handed around</h2>
 * Claims are genuinely optional here: a mannequin does not have to belong to one, and this module
 * works exactly as it always has on a server with no claims plugin installed at all. If {@code
 * MannequinEditMenu} or {@code MannequinServices} held a {@code ClaimServices} field directly, loading
 * either class would require resolving a type that, on such a server, simply is not on the classpath —
 * turning "claims is not installed" into a hard crash the moment anybody opened a mannequin's own edit
 * page. Routed through this interface instead, only {@link ClaimIntegration} and the handful of classes
 * beside it ever mention a claims-module type, and only after already confirming the real thing exists.
 *
 * @see ClaimIntegration
 */
public interface ClaimLink {

    /** Does nothing at all — what every mannequin screen gets on a server with no claims plugin. */
    ClaimLink NONE = new ClaimLink() {
        @Override
        public Optional<UUID> claimAround(Player player) {
            return Optional.empty();
        }

        @Override
        public boolean owns(UUID claimId, UUID player) {
            return false;
        }

        @Override
        public String nameOf(UUID claimId) {
            return null;
        }
    };

    /** The claim this player is standing in right now, if any. */
    Optional<UUID> claimAround(Player player);

    /** Whether this player owns the claim with this id. {@code false} for an id that no longer exists. */
    boolean owns(UUID claimId, UUID player);

    /** The claim's own name, or {@code null} for an id that no longer exists. */
    String nameOf(UUID claimId);
}
