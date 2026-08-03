package de.raindancer.modules.claims.store;

import de.raindancer.modules.claims.rules.ClaimAreaRule;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.core.world.protection.LandProvider;
import de.raindancer.core.world.protection.ProtectedArea;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claims, offered to the rest of the server as protected ground.
 *
 * <p>This is the one object that makes claims visible to everything else. Registered with
 * {@code RainsCore.land()} when the module starts, and stood down when it stops — after which Core answers
 * {@code UNKNOWN} to everything rather than pretending nothing is protected.
 *
 * <h2>Why presence is tracked here</h2>
 * Not an optimisation. {@link ClaimRegistry#at(Location, Claim)} gives the claim a player was last in the
 * benefit of the doubt when the new position is ambiguous, and ambiguous is the common case: standing on a
 * claim's own roof, jumping at the border, walking along a wall two claims share. A raw lookup flickers between
 * two answers several times a second, and every flicker is an entry, an exit, a border flash and a flag that
 * changes value mid-jump.
 *
 * <p>So the tracking lives with the data rather than in Core. Core asks {@link #around} and gets a steady
 * answer; another region plugin that does not need the smoothing simply inherits the default.
 */
public final class ClaimLandProvider implements LandProvider {

    private final ClaimRegistry claims;

    /** The claim each player is currently considered to be in, by claim id. */
    private final Map<UUID, UUID> whereEachPlayerIs = new ConcurrentHashMap<>();

    public ClaimLandProvider(ClaimRegistry claims) {
        this.claims = claims;
    }

    @Override
    public String name() {
        return "Rain's Claims";
    }

    @Override
    public Optional<ProtectedArea> at(Location location) {
        return claims.at(location).map(ClaimAreaRule::new);
    }

    @Override
    public Optional<ProtectedArea> around(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        Claim remembered = lastKnown(player.getUniqueId()).orElse(null);
        return claims.at(player.getLocation(), remembered).map(ClaimAreaRule::new);
    }

    @Override
    public boolean hasAnyIn(World world) {
        return !claims.inWorld(world.getUID()).isEmpty();
    }

    // ------------------------------------------------------------------------ presence

    /** The claim a player was last seen in, without looking anything up. */
    public Optional<Claim> lastKnown(UUID player) {
        UUID claimId = whereEachPlayerIs.get(player);
        return claimId == null ? Optional.empty() : claims.byId(claimId);
    }

    /**
     * Records where a player now is.
     *
     * @param claim null for standing on unclaimed ground
     * @return whether this is a change, which is what makes it worth sending a message about
     */
    public boolean moved(UUID player, Claim claim) {
        UUID was = claim == null
                ? whereEachPlayerIs.remove(player)
                : whereEachPlayerIs.put(player, claim.id());
        UUID now = claim == null ? null : claim.id();
        return !java.util.Objects.equals(was, now);
    }

    /**
     * Called when a player leaves.
     *
     * <p>Not optional: without it the map grows by one entry for every player who has ever been on the server,
     * and a returning player is briefly considered to be in whichever claim they were in months ago.
     */
    public void forget(UUID player) {
        whereEachPlayerIs.remove(player);
    }

    /** Everybody currently considered to be inside this claim. */
    public java.util.Set<UUID> inside(Claim claim) {
        java.util.Set<UUID> people = new java.util.HashSet<>();
        whereEachPlayerIs.forEach((player, claimId) -> {
            if (claimId.equals(claim.id())) {
                people.add(player);
            }
        });
        return people;
    }

    /** How many players are being tracked — for the diagnostics that answer "is this leaking". */
    public int tracked() {
        return whereEachPlayerIs.size();
    }
}
