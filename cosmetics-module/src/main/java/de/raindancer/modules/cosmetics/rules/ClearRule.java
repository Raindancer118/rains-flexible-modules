package de.raindancer.modules.cosmetics.rules;

import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.cosmetics.model.ClearScope;

/**
 * Whether somebody's cosmetics may be taken off, and which of them there are to take.
 *
 * <p>A particle lives in the player's own persistent data, so for somebody who is not online it is out
 * of reach — which is a refusal when it is all that was asked for, and merely a note when the name could
 * still be cleared.
 */
public final class ClearRule extends AbstractRule<ClearRule.Request> implements ICosmeticsRule {

    public ClearRule() {
        super("cosmetics are cleared by their owner or by staff, and only what is actually worn");
    }

    /**
     * @param self           whether the one clearing is the one being cleared
     * @param maySelf        holds the node for clearing their own
     * @param mayOthers      holds the node for clearing somebody else's
     * @param targetOnline   whether the target's particle can be reached right now
     * @param wearsName      whether the target has a name style at all
     * @param wearsParticle  whether the target wears a particle; unknown for offline targets, so false
     */
    public record Request(ClearScope scope, boolean self, boolean maySelf, boolean mayOthers,
                          boolean targetOnline, boolean wearsName, boolean wearsParticle) {
    }

    /** What clearing would actually do. */
    public record Plan(boolean name, boolean particles, boolean particlesOutOfReach) {
    }

    @Override
    public Verdict judge(Request request) {
        if (request.self() && !request.maySelf()) {
            return Verdict.refused("cosmetics.clear.not-allowed");
        }
        if (!request.self() && !request.mayOthers()) {
            return Verdict.refused("cosmetics.clear.not-allowed-others");
        }
        if (request.scope() == ClearScope.PARTICLES && !request.targetOnline()) {
            return Verdict.refused("cosmetics.clear.offline-particles");
        }
        Plan plan = plan(request);
        if (!plan.name() && !plan.particles()) {
            return Verdict.refused("cosmetics.clear.nothing");
        }
        return Verdict.allowed();
    }

    public Plan plan(Request request) {
        boolean name = request.scope().includesName() && request.wearsName();
        boolean reachable = request.targetOnline();
        boolean particles = request.scope().includesParticles() && reachable && request.wearsParticle();
        boolean outOfReach = request.scope().includesParticles() && !reachable;
        return new Plan(name, particles, outOfReach);
    }
}
