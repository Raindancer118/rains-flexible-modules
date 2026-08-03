package de.raindancer.modules.claims.model;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Somebody trying to claim a piece of ground, as one thing the rules can be asked about.
 *
 * <p>A record rather than seven arguments threaded through nine checks. That is what lets each rule be a class
 * with one method and a test that constructs one of these — as opposed to a hundred-line {@code validate} whose
 * sixth branch could only be reached by building a whole world.
 *
 * @param claimant who is asking
 * @param world    where
 * @param shape    the outline they drew
 * @param name     what they want to call it, or null when the shape is being judged on its own
 * @param existing the claim being reshaped, or null for a new one — the rules that look for overlaps have to
 *                 know not to count it against itself
 */
public record ClaimAttempt(Player claimant, World world, ClaimShape shape, String name, Claim existing) {

    /** A brand new claim. */
    public static ClaimAttempt toCreate(Player claimant, World world, ClaimShape shape, String name) {
        return new ClaimAttempt(claimant, world, shape, name, null);
    }

    /** Redrawing one that is already there. */
    public static ClaimAttempt toReshape(Player claimant, World world, ClaimShape shape, Claim existing) {
        return new ClaimAttempt(claimant, world, shape, null, existing);
    }

    public UUID claimantId() {
        return claimant.getUniqueId();
    }

    /** The claim not to count against itself, when there is one. */
    public UUID ignoring() {
        return existing == null ? null : existing.id();
    }

    public boolean isReshape() {
        return existing != null;
    }
}
