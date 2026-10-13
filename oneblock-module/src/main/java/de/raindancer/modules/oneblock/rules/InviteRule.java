package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.IslandFacts;
import de.raindancer.modules.oneblock.model.Requester;

import java.util.Optional;
import java.util.UUID;

/**
 * Whether an invite may go out. The team side asks the island's own rank setting for BentoBox's invite command, so an
 * owner who lets members invite in /ob settings is obeyed here too; a guest may be brought by anybody on the team.
 * BentoBox checks again when the invite is accepted — this is what lets the inviter hear "no" at once.
 */
public final class InviteRule implements IOneBlockRule {

    private final int memberRank;

    public InviteRule(int memberRank) {
        this.memberRank = memberRank;
    }

    /** @return the message key of the refusal, empty when the invite may go */
    public Optional<String> judge(InviteKind kind, Requester from, UUID to, Optional<IslandFacts> island) {
        if (from.id().equals(to)) {
            return Optional.of("oneblock.refuse.self");
        }
        if (island.isEmpty()) {
            return Optional.of("oneblock.refuse.no-island");
        }
        IslandFacts facts = island.get();
        if (facts.inviteeMember()) {
            return Optional.of("oneblock.refuse.already-member");
        }
        if (facts.inviteeBanned()) {
            return Optional.of("oneblock.refuse.banned");
        }
        int needed = kind == InviteKind.TEAM ? facts.teamInviteRank() : memberRank;
        if (facts.inviterRank() < needed) {
            return Optional.of("oneblock.refuse.rank");
        }
        if (kind == InviteKind.TEAM && facts.mostMembers() >= 0 && facts.members() >= facts.mostMembers()) {
            return Optional.of("oneblock.refuse.full");
        }
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "who may invite whom to an island";
    }
}
