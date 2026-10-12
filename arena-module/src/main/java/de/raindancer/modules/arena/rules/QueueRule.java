package de.raindancer.modules.arena.rules;

import java.util.Optional;

/**
 * Whether a fight may be waited for at all — the one gate every way into a fight passes, the queue, an accepted
 * invite and an arrival from survival alike, so no way round it can skip the stake limit or ask for a kit nobody made.
 */
public final class QueueRule implements IArenaRule {

    public record Facts(boolean inFight, boolean anyPit, boolean kitKnown, long stake, long mostStake,
                        boolean needsSurvival, boolean survivalReachable) {
    }

    public Optional<String> refusal(Facts facts) {
        if (facts.inFight()) {
            return Optional.of("arena.queue.in-fight");
        }
        if (!facts.anyPit()) {
            return Optional.of("arena.queue.no-pits");
        }
        if (!facts.kitKnown()) {
            return Optional.of("arena.invite.refuse.no-kit");
        }
        if (facts.stake() < 0 || (facts.mostStake() > 0 && facts.stake() > facts.mostStake())) {
            return Optional.of("arena.invite.refuse.stake");
        }
        if (facts.needsSurvival() && !facts.survivalReachable()) {
            return Optional.of("arena.queue.no-survival");
        }
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "what may be waited for: a known kit, a stake within the limit, survival reachable when needed";
    }
}
