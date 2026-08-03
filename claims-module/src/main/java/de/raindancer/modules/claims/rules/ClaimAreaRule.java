package de.raindancer.modules.claims.rules;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimMember;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.core.world.protection.ProtectedArea;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A claim, seen as the piece of protected ground Core knows how to enforce.
 *
 * <p>An adapter rather than {@code Claim implements ProtectedArea}, for two reasons. {@code Claim.id()} answers
 * a {@link UUID} and the interface wants a string, which is not a difference worth contorting the model over.
 * And more importantly it keeps the direction of the dependency honest: a claim is a claim, and being
 * enforceable ground is a role it plays, not what it is.
 *
 * <h2>The decision that lives here</h2>
 * {@link #may} is the whole per-player rule, and the order matters:
 *
 * <ol>
 *   <li><b>An owner</b> may do anything — including a banned owner, so a co-owner cannot lock the other one
 *       out of their own claim.</li>
 *   <li><b>A banned player</b> may do nothing, and this beats an explicit grant. Banning somebody who happens
 *       to be trusted has to actually stop them, or the ban is decoration.</li>
 *   <li><b>Somebody named in the claim</b> gets exactly what they were given, and <em>not</em> the public grant
 *       as well. Otherwise taking a permission away from a trusted player would silently do nothing whenever
 *       it was public too. The one exception is that a claim admin may always walk in — "may manage the
 *       members" is useless to somebody who cannot get through the door.</li>
 *   <li><b>Anybody else</b> gets whatever the owner left public.</li>
 * </ol>
 *
 * <p>The admin bypass is deliberately <em>not</em> here. It is Core's, checked before this is ever consulted,
 * so a region plugin cannot forget to honour it.
 */
public record ClaimAreaRule(Claim claim) implements ProtectedArea, IClaimRule {

    @Override
    public String describe() {
        return "who may do what inside a claim";
    }


    @Override
    public String id() {
        return claim.id().toString();
    }

    /**
     * Named for a human, and its own name is not enough.
     *
     * <p>"home" tells a player nothing about whose doorstep they are on, and claim names are unique per owner
     * rather than per server, so several claims called "home" is the normal case rather than the odd one.
     */
    @Override
    public String name() {
        return claim.name();
    }

    @Override
    public List<UUID> owners() {
        return List.copyOf(claim.owners());
    }

    @Override
    public Optional<Boolean> flagOverride(LandFlag flag, LandAudience audience) {
        return claim.flagOverride(flag, audience);
    }

    @Override
    public LandAudience audienceOf(UUID who) {
        if (who == null) {
            return LandAudience.VISITOR;
        }
        if (claim.isOwner(who)) {
            return LandAudience.OWNER;
        }
        return claim.member(who).isPresent() ? LandAudience.TRUSTED : LandAudience.VISITOR;
    }

    @Override
    public boolean may(UUID who, LandAction action) {
        if (who == null) {
            return claim.publicHas(action);
        }
        if (claim.isOwner(who)) {
            return true;
        }
        if (claim.activeBan(who).isPresent()) {
            return false;
        }
        Optional<ClaimMember> named = claim.member(who);
        if (named.isPresent()) {
            ClaimMember member = named.get();
            if (member.isClaimAdmin() && action == LandAction.ENTER) {
                return true;
            }
            return member.has(action);
        }
        return claim.publicHas(action);
    }
}
