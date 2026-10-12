package de.raindancer.modules.arena.rules;

import java.util.Optional;

/** Whether somebody may ask somebody else to a fight now, and if not, which sentence says why. */
public final class InviteRule implements IArenaRule {

    public record Facts(boolean self, boolean targetKnown, boolean senderBusy, boolean targetBusy,
                        boolean alreadyAsked, long stake, long mostStake, boolean kitKnown) {
    }

    public Optional<String> refusal(Facts facts) {
        if (facts.self()) {
            return Optional.of("arena.invite.refuse.self");
        }
        if (!facts.targetKnown()) {
            return Optional.of("arena.invite.refuse.unknown");
        }
        if (!facts.kitKnown()) {
            return Optional.of("arena.invite.refuse.no-kit");
        }
        if (facts.senderBusy()) {
            return Optional.of("arena.invite.refuse.you-busy");
        }
        if (facts.targetBusy()) {
            return Optional.of("arena.invite.refuse.busy");
        }
        if (facts.alreadyAsked()) {
            return Optional.of("arena.invite.refuse.already");
        }
        if (facts.stake() < 0 || (facts.mostStake() > 0 && facts.stake() > facts.mostStake())) {
            return Optional.of("arena.invite.refuse.stake");
        }
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "who may ask whom to a fight";
    }
}
