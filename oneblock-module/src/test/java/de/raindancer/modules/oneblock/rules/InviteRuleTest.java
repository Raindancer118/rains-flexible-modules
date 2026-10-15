package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.IslandFacts;
import de.raindancer.modules.oneblock.model.Requester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InviteRuleTest {

    private static final int MEMBER = 500;
    private static final int OWNER = 1000;
    private final InviteRule rule = new InviteRule(MEMBER);
    private final Requester ada = new Requester(UUID.randomUUID(), "Ada");
    private final UUID bo = UUID.randomUUID();

    private static IslandFacts island(int rank, int members, int most) {
        return new IslandFacts("is-1", rank, OWNER, members, most, false, false, de.raindancer.modules.oneblock.model.EconomyMode.PURE);
    }

    @Test
    @DisplayName("an owner with room in the team may invite a member, and anybody on the team may invite a guest")
    void allowed() {
        assertThat(rule.judge(InviteKind.TEAM, ada, bo, Optional.of(island(OWNER, 1, 4)))).isEmpty();
        assertThat(rule.judge(InviteKind.GUEST, ada, bo, Optional.of(island(MEMBER, 1, 4)))).isEmpty();
    }

    @Test
    @DisplayName("each refusal names its reason")
    void refusals() {
        assertThat(rule.judge(InviteKind.TEAM, ada, ada.id(), Optional.of(island(OWNER, 1, 4))))
                .contains("oneblock.refuse.self");
        assertThat(rule.judge(InviteKind.GUEST, ada, bo, Optional.empty())).contains("oneblock.refuse.no-island");
        assertThat(rule.judge(InviteKind.TEAM, ada, bo, Optional.of(island(MEMBER, 1, 4))))
                .as("the island says only the owner invites members").contains("oneblock.refuse.rank");
        assertThat(rule.judge(InviteKind.TEAM, ada, bo, Optional.of(island(OWNER, 4, 4)))).contains("oneblock.refuse.full");
        assertThat(rule.judge(InviteKind.GUEST, ada, bo, Optional.of(island(MEMBER - 1, 1, 4))))
                .as("coops and visitors cannot bring guests").contains("oneblock.refuse.rank");
        assertThat(rule.judge(InviteKind.TEAM, ada, bo,
                Optional.of(new IslandFacts("is-1", OWNER, OWNER, 2, 4, true, false, de.raindancer.modules.oneblock.model.EconomyMode.PURE)))).contains("oneblock.refuse.already-member");
        assertThat(rule.judge(InviteKind.GUEST, ada, bo,
                Optional.of(new IslandFacts("is-1", OWNER, OWNER, 2, 4, false, true, de.raindancer.modules.oneblock.model.EconomyMode.PURE)))).contains("oneblock.refuse.banned");
    }

    @Test
    @DisplayName("a team limit below zero is no limit")
    void unlimited() {
        assertThat(rule.judge(InviteKind.TEAM, ada, bo, Optional.of(island(OWNER, 40, -1)))).isEmpty();
    }
}
