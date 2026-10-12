package de.raindancer.modules.arenagate.service;

import de.raindancer.modules.arena.api.MatchSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentsAndInvitesTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();

    @Test
    @DisplayName("a stake is taken only from somebody who agreed to at least that much, once, and only for a while")
    void stakeConsent() {
        GateConsents consents = new GateConsents(clock::get);
        consents.agree(ada, 100, false);

        assertThat(consents.takeStake(ada, 150)).as("more than agreed").isFalse();
        assertThat(consents.takeStake(bo, 50)).as("never agreed").isFalse();
        assertThat(consents.takeStake(ada, 100)).isTrue();
        assertThat(consents.takeStake(ada, 100)).as("used up").isFalse();

        consents.agree(ada, 100, false);
        clock.addAndGet(GateConsents.LIFETIME + 1);
        assertThat(consents.takeStake(ada, 100)).as("too long ago").isFalse();
    }

    @Test
    @DisplayName("a copy of somebody's gear is handed out only when they chose to fight in it")
    void gearConsent() {
        GateConsents consents = new GateConsents(clock::get);

        assertThat(consents.takeGear(ada)).isFalse();
        consents.agree(ada, 0, true);
        assertThat(consents.takeGear(ada)).isTrue();
    }

    @Test
    @DisplayName("a bare accept takes the invite only when there is exactly one; with two it takes none")
    void acceptIsUnambiguous() {
        GateInvites invites = new GateInvites(clock::get);
        invites.add("a1", ada, "Ada", bo, MatchSpec.duel("knight"), false);

        assertThat(invites.take(bo, null)).isPresent();

        invites.add("a2", ada, "Ada", bo, MatchSpec.duel("knight"), false);
        invites.add("a3", UUID.randomUUID(), "Cy", bo, MatchSpec.duel("knight").withStake(10_000), false);
        assertThat(invites.take(bo, null)).as("which one would be a guess").isEmpty();
        assertThat(invites.take(bo, "a3")).isPresent();
    }

    @Test
    @DisplayName("somebody is summoned to the arena only for an invite they really made from here")
    void summonNeedsAnInvite() {
        GateInvites invites = new GateInvites(clock::get);
        invites.outgoing("x9", ada);

        assertThat(invites.madeBy("x9", bo)).isFalse();
        assertThat(invites.madeBy("nope", ada)).isFalse();
        assertThat(invites.madeBy("x9", ada)).isTrue();
        assertThat(invites.madeBy("x9", ada)).as("once").isFalse();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("an expired invite no longer counts as asked, and the one who sent it can take it back")
    void expiredAndCancelled() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000);
        java.util.UUID ada = java.util.UUID.randomUUID();
        java.util.UUID bo = java.util.UUID.randomUUID();
        GateInvites invites = new GateInvites(clock::get);
        invites.add("a1", ada, "Ada", bo, MatchSpec.duel("knight"), false);
        assertThat(invites.asked(ada, bo)).isTrue();
        clock.addAndGet(60_001);
        assertThat(invites.asked(ada, bo)).as("five minutes later it is long gone").isFalse();

        invites.add("a2", ada, "Ada", bo, MatchSpec.duel("knight"), false);
        assertThat(invites.cancel(bo, "a2")).as("only the one who asked").isEmpty();
        assertThat(invites.cancel(ada, "a2")).isPresent();
        assertThat(invites.forPlayer(bo)).isEmpty();
        assertThat(invites.cancel(ada, "a2")).isEmpty();
    }
}
