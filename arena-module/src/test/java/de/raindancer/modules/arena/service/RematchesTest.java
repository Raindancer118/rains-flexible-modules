package de.raindancer.modules.arena.service;

import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RematchesTest {

    private static final UUID ADA = UUID.randomUUID();
    private static final UUID BO = UUID.randomUUID();
    private static final UUID CY = UUID.randomUUID();
    private static final MatchSpec DUEL = new MatchSpec(Mode.DUEL, "archer", 3, 50, 1, null);

    private final Rematches rematches = new Rematches();

    @Test
    @DisplayName("both ask and the same fight is ready again, same sides, same kit, rounds and stake")
    void bothAsk() {
        rematches.offer(List.of(Set.of(ADA), Set.of(BO)), DUEL, 1_000);

        Rematches.Answer first = rematches.ask(ADA, 2_000);
        assertThat(first.kind()).isEqualTo(Rematches.Kind.WAITING);
        assertThat(first.missing()).containsExactly(BO);

        Rematches.Answer second = rematches.ask(BO, 3_000);
        assertThat(second.kind()).isEqualTo(Rematches.Kind.READY);
        assertThat(second.group().spec()).isEqualTo(DUEL);
        assertThat(second.group().sides()).containsExactly(Set.of(ADA), Set.of(BO));
        assertThat(rematches.ask(ADA, 3_500).kind()).as("used up").isEqualTo(Rematches.Kind.NONE);
    }

    @Test
    @DisplayName("asking twice is still waiting, not ready")
    void twice() {
        rematches.offer(List.of(Set.of(ADA), Set.of(BO)), DUEL, 0);
        rematches.ask(ADA, 10);
        assertThat(rematches.ask(ADA, 20).kind()).isEqualTo(Rematches.Kind.WAITING);
    }

    @Test
    @DisplayName("an offer runs out after a minute")
    void expires() {
        rematches.offer(List.of(Set.of(ADA), Set.of(BO)), DUEL, 0);
        assertThat(rematches.ask(ADA, Rematches.LIFETIME_MS + 1).kind()).isEqualTo(Rematches.Kind.EXPIRED);
        assertThat(rematches.ask(BO, Rematches.LIFETIME_MS + 2).kind()).isEqualTo(Rematches.Kind.NONE);
    }

    @Test
    @DisplayName("somebody leaving takes the offer away for everybody in it")
    void forget() {
        rematches.offer(List.of(Set.of(ADA), Set.of(BO)), DUEL, 0);
        assertThat(rematches.forget(BO)).containsExactly(ADA);
        assertThat(rematches.ask(ADA, 10).kind()).isEqualTo(Rematches.Kind.NONE);
    }

    @Test
    @DisplayName("a newer fight replaces an older offer; a free for all needs everybody")
    void newerAndFfa() {
        rematches.offer(List.of(Set.of(ADA), Set.of(BO)), DUEL, 0);
        MatchSpec ffa = new MatchSpec(Mode.FFA, "gladiator", 1, 0, 1, null);
        rematches.offer(List.of(Set.of(ADA), Set.of(BO), Set.of(CY)), ffa, 100);
        rematches.ask(ADA, 200);
        Rematches.Answer bo = rematches.ask(BO, 300);
        assertThat(bo.kind()).isEqualTo(Rematches.Kind.WAITING);
        assertThat(bo.missing()).containsExactly(CY);
        assertThat(rematches.ask(CY, 400).group().spec()).isEqualTo(ffa);
    }

    @Test
    @DisplayName("mob waves get no rematch offer, and nor does a fight of one")
    void notForWaves() {
        assertThat(Rematches.offered(MatchSpec.waves(null, "gladiator"), 2)).isFalse();
        assertThat(Rematches.offered(DUEL, 1)).isFalse();
        assertThat(Rematches.offered(DUEL, 2)).isTrue();
    }
}
