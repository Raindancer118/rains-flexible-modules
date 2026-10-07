package de.raindancer.modules.claims.service;

import de.raindancer.core.world.poi.ClaimWarps;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimPoint;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A claim's warp — its front door, which roads already head for — and the claim each owner calls home.
 *
 * <p>Written into Core's {@link ClaimWarps}, where a warps plugin finds them for {@code /warp claim} and
 * {@code /warp home <player>} without knowing this module exists. Who may then go there is the claim's
 * own teleport-in rule, enforced on arrival as for any teleport. What this class owns is keeping the two
 * in step: a warp that outlives its claim, or keeps an old owner's name, sends people to the wrong land.
 */
public final class ClaimWarpService implements IClaimService {

    /** What came of asking. */
    public enum Outcome { SET, CLEARED, HOME, OUTSIDE, NOT_ALLOWED, NO_WARP }

    private final ClaimWarps warps;

    public ClaimWarpService(ClaimWarps warps) {
        this.warps = warps;
    }

    @Override
    public void settings(ClaimSettings settings) {
        // Nothing to read: who may set a claim's warp is who owns the claim.
    }

    /** Sets the claim's warp, and front door, where somebody stands. Its owners, or staff. */
    public Outcome set(UUID who, boolean staff, Claim claim, String world, double x, double y, double z,
                       float yaw, float pitch) {
        if (!staff && !claim.isOwner(who)) {
            return Outcome.NOT_ALLOWED;
        }
        // Inside the claim itself — its world, its column and its height. A point merely above it, or in
        // another world at the same coordinates, is not covered by the claim's teleport-in rules, and a
        // warp there would be a way past them listed under the claim's name.
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        if (world == null || !world.equals(claim.worldName()) || !claim.shape().containsBlock(bx, by, bz)) {
            return Outcome.OUTSIDE;
        }
        if (!claim.entrance(new ClaimPoint(bx, bz), by)) {
            return Outcome.OUTSIDE;
        }
        warps.set(id(claim), claim.name(), claim.primaryOwner(), world, x, y, z, yaw, pitch);
        return Outcome.SET;
    }

    /** Takes the warp away — the front door goes with it, and so does anybody's home there. */
    public Outcome clear(UUID who, boolean staff, Claim claim) {
        if (!staff && !claim.isOwner(who)) {
            return Outcome.NOT_ALLOWED;
        }
        claim.clearEntrance();
        warps.remove(id(claim));
        return Outcome.CLEARED;
    }

    /** Makes this claim {@code who}'s main home. Their own claims only, and only one with a warp. */
    public Outcome makeHome(UUID who, Claim claim) {
        if (!claim.isOwner(who)) {
            return Outcome.NOT_ALLOWED;
        }
        return warps.markMain(who, id(claim)) ? Outcome.HOME : Outcome.NO_WARP;
    }

    /** Takes away {@code who}'s main home; the claim keeps its warp. */
    public boolean clearHome(UUID who) {
        return warps.clearMain(who);
    }

    // ------------------------------------------------------------------ following the claim

    public void renamed(Claim claim) {
        warps.rename(id(claim), claim.name());
    }

    /** A new owner: the warp is theirs, and nobody's home until somebody chooses it again. */
    public void transferred(Claim claim) {
        warps.reassign(id(claim), claim.primaryOwner());
    }

    /** A co-owner left: it is not theirs to call home any more. */
    public void ownerRemoved(Claim claim, UUID formerOwner) {
        warps.dropHomeOf(formerOwner, id(claim));
        // Listed under whoever owns it first now. Set again rather than reassigned: reassigning is for a
        // hand-over and clears everybody's home, and the co-owners who stay keep theirs.
        warps.forClaim(id(claim))
                .filter(point -> formerOwner.equals(point.owner()) && claim.primaryOwner() != null)
                .ifPresent(point -> warps.set(id(claim), claim.name(), claim.primaryOwner(), point.world(),
                        point.x(), point.y(), point.z(), point.yaw(), point.pitch()));
    }

    /** The claim changed shape or height: a warp now outside it goes, front door and all. */
    public void reshaped(Claim claim) {
        warps.forClaim(id(claim))
                .filter(point -> !claim.shape().containsBlock((int) Math.floor(point.x()),
                        (int) Math.floor(point.y()), (int) Math.floor(point.z())))
                .ifPresent(point -> {
                    claim.clearEntrance();
                    warps.remove(id(claim));
                });
    }

    public void deleted(Claim claim) {
        warps.remove(id(claim));
    }

    /**
     * Brings the warps in line with the claims, at start: a front door set before claim warps existed
     * becomes one, and a warp whose claim was deleted while this module was not running goes.
     *
     * @param worldNames a claim's world by id, for a claim that does not carry its world's name
     */
    public void catchUp(Collection<Claim> claims, Function<UUID, String> worldNames) {
        Set<String> existing = claims.stream().map(ClaimWarpService::id).collect(Collectors.toSet());
        for (Poi point : warps.all()) {
            if (!existing.contains(ClaimWarps.claimOf(point))) {
                warps.remove(ClaimWarps.claimOf(point));
            }
        }
        for (Claim claim : claims) {
            // A warp left outside its claim by an older version, or a reshape while this was not running.
            reshaped(claim);
            if (claim.entrance().isEmpty() || warps.forClaim(id(claim)).isPresent()) {
                continue;
            }
            String world = claim.worldName() == null || claim.worldName().isBlank()
                    ? worldNames.apply(claim.worldId()) : claim.worldName();
            if (world == null) {
                continue;
            }
            ClaimPoint door = claim.entrance().get();
            warps.set(id(claim), claim.name(), claim.primaryOwner(), world,
                    door.x() + 0.5, claim.entranceY(), door.z() + 0.5, 0, 0);
        }
    }

    private static String id(Claim claim) {
        return claim.id().toString();
    }

    @Override
    public String describe() {
        return "a claim's warp, and the claim each owner calls home";
    }
}
