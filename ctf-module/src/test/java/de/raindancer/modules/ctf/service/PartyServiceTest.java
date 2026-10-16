package de.raindancer.modules.ctf.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("parties")
class PartyServiceTest {

    private final UUID amy = UUID.nameUUIDFromBytes("amy".getBytes());
    private final UUID bob = UUID.nameUUIDFromBytes("bob".getBytes());
    private final UUID cat = UUID.nameUUIDFromBytes("cat".getBytes());
    private final AtomicLong now = new AtomicLong();
    private final PartyService parties = new PartyService(now::get);

    @Test
    @DisplayName("an invite accepted makes a party of two, led by who invited")
    void invite() {
        assertThat(parties.invite(amy, bob)).isEqualTo(PartyService.Outcome.INVITED);
        assertThat(parties.accept(bob)).isEqualTo(PartyService.Outcome.JOINED);
        assertThat(parties.of(bob).orElseThrow().leader()).isEqualTo(amy);
        assertThat(parties.of(amy).orElseThrow().members()).containsExactly(amy, bob);
    }

    @Test
    @DisplayName("only the leader invites; an invite runs out")
    void rules() {
        parties.invite(amy, bob);
        parties.accept(bob);
        assertThat(parties.invite(bob, cat)).isEqualTo(PartyService.Outcome.NOT_LEADER);
        parties.invite(amy, cat);
        now.addAndGet(PartyService.INVITE_MILLIS + 1);
        assertThat(parties.accept(cat)).isEqualTo(PartyService.Outcome.NO_INVITE);
        assertThat(parties.invite(amy, amy)).isEqualTo(PartyService.Outcome.SELF);
    }

    @Test
    @DisplayName("the leader leaving hands the party on; the last two parting ends it")
    void leaving() {
        parties.invite(amy, bob);
        parties.accept(bob);
        parties.invite(amy, cat);
        parties.accept(cat);
        parties.leave(amy);
        assertThat(parties.of(bob).orElseThrow().leader()).isEqualTo(bob);
        parties.forget(cat);
        assertThat(parties.of(bob)).as("alone is no party").isEmpty();
    }

    @Test
    @DisplayName("the leader can send somebody out, nobody else can")
    void kick() {
        parties.invite(amy, bob);
        parties.accept(bob);
        assertThat(parties.kick(bob, amy)).isFalse();
        assertThat(parties.kick(amy, bob)).isTrue();
        assertThat(parties.of(amy)).isEmpty();
    }
}
