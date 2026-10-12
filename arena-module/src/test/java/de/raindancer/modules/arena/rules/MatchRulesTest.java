package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.model.Pit;
import de.raindancer.modules.arena.model.Place;
import de.raindancer.modules.arena.model.QueueEntry;
import de.raindancer.modules.arena.api.WaveSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchRulesTest {

    private static QueueEntry waiting(MatchSpec spec, long since) {
        return new QueueEntry(UUID.randomUUID(), spec, since);
    }

    // ── the queue ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("two waiting for the same duel make one; somebody waiting for another kit is left waiting")
    void duelPairs() {
        MatchSpec gladiator = MatchSpec.duel("gladiator");
        List<QueueEntry> queue = List.of(waiting(gladiator, 1), waiting(MatchSpec.duel("knight"), 2),
                waiting(gladiator, 3));

        List<PairingRule.Group> groups = new PairingRule().groups(queue, 10, 20_000);

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().players()).containsExactlyInAnyOrder(queue.get(0).player(), queue.get(2).player());
        assertThat(groups.getFirst().sides()).hasSize(2);
    }

    @Test
    @DisplayName("a different stake is a different fight — nobody is matched into paying more than they chose")
    void stakeSeparates() {
        List<QueueEntry> queue = List.of(waiting(MatchSpec.duel("knight").withStake(100), 1),
                waiting(MatchSpec.duel("knight"), 2));

        assertThat(new PairingRule().groups(queue, 10, 20_000)).isEmpty();
    }

    @Test
    @DisplayName("oldest first: of three waiting for a duel, the first two fight")
    void oldestFirst() {
        MatchSpec spec = MatchSpec.duel("knight");
        List<QueueEntry> queue = List.of(waiting(spec, 1), waiting(spec, 2), waiting(spec, 3));

        PairingRule.Group group = new PairingRule().groups(queue, 10, 20_000).getFirst();

        assertThat(group.players()).doesNotContain(queue.get(2).player());
    }

    @Test
    @DisplayName("teams of two: four make a fight, split two and two")
    void teams() {
        MatchSpec spec = new MatchSpec(Mode.TEAMS, "knight", 1, 0, 2, null);
        List<QueueEntry> queue = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            queue.add(waiting(spec, i));
        }

        PairingRule.Group group = new PairingRule().groups(queue, 10, 20_000).getFirst();

        assertThat(group.sides()).hasSize(2).allSatisfy(side -> assertThat(side).hasSize(2));
    }

    @Test
    @DisplayName("free for all waits for more, then starts with three once the third has waited long enough")
    void freeForAll() {
        MatchSpec spec = new MatchSpec(Mode.FFA, "knight", 1, 0, 1, null);
        List<QueueEntry> queue = List.of(waiting(spec, 0), waiting(spec, 1), waiting(spec, 1_000));
        PairingRule rule = new PairingRule();

        assertThat(rule.groups(queue, 5_000, 20_000)).as("not long enough yet").isEmpty();
        assertThat(rule.groups(queue, 21_000, 20_000)).hasSize(1);
        assertThat(rule.groups(queue, 21_000, 20_000).getFirst().sides()).hasSize(3);
    }

    @Test
    @DisplayName("a mob-wave run starts at once with just the one player")
    void wavesSolo() {
        List<QueueEntry> queue = List.of(waiting(MatchSpec.waves(WaveSpec.PRESETS.get("undead"), "knight"), 1));

        assertThat(new PairingRule().groups(queue, 2, 20_000)).hasSize(1);
    }

    // ── pits ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the next fight goes to the first pit that is set up and free")
    void pits() {
        Pit unfinished = Pit.named("a", "arena");
        Pit busy = ready("b");
        Pit free = ready("c");

        Optional<Pit> chosen = new PitRule().free(List.of(unfinished, busy, free), Set.of("b"));

        assertThat(chosen).contains(free);
        assertThat(new PitRule().free(List.of(busy), Set.of("b"))).isEmpty();
    }

    private static Pit ready(String id) {
        return Pit.named(id, "arena").withFloor(new Box("arena", 0, 64, 0, 10, 70, 10))
                .withSpawn(new Place("arena", 1, 65, 1, 0, 0)).withSpawn(new Place("arena", 9, 65, 9, 0, 0));
    }

    // ── invites ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("each reason not to ask somebody is its own sentence")
    void invites() {
        InviteRule rule = new InviteRule();
        InviteRule.Facts ok = new InviteRule.Facts(false, true, false, false, false, 100, 10_000, true);

        assertThat(rule.refusal(ok)).isEmpty();
        assertThat(rule.refusal(new InviteRule.Facts(true, true, false, false, false, 0, 0, true)))
                .contains("arena.invite.refuse.self");
        assertThat(rule.refusal(new InviteRule.Facts(false, false, false, false, false, 0, 0, true)))
                .contains("arena.invite.refuse.unknown");
        assertThat(rule.refusal(new InviteRule.Facts(false, true, false, false, false, 0, 0, false)))
                .contains("arena.invite.refuse.no-kit");
        assertThat(rule.refusal(new InviteRule.Facts(false, true, true, false, false, 0, 0, true)))
                .contains("arena.invite.refuse.you-busy");
        assertThat(rule.refusal(new InviteRule.Facts(false, true, false, true, false, 0, 0, true)))
                .contains("arena.invite.refuse.busy");
        assertThat(rule.refusal(new InviteRule.Facts(false, true, false, false, true, 0, 0, true)))
                .contains("arena.invite.refuse.already");
        assertThat(rule.refusal(new InviteRule.Facts(false, true, false, false, false, 20_000, 10_000, true)))
                .contains("arena.invite.refuse.stake");
    }

    // ── waves ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("each wave is bigger by the growth, a second player adds half again, and nothing is bigger than the cap")
    void waveSizes() {
        WavePlanRule rule = new WavePlanRule();
        WaveSpec spec = new WaveSpec("x", 10, WaveSpec.Selection.RANDOM, List.of("ZOMBIE"), 4, 2);

        assertThat(rule.size(spec, 1, 1)).isEqualTo(4);
        assertThat(rule.size(spec, 3, 1)).isEqualTo(8);
        assertThat(rule.size(spec, 3, 2)).isEqualTo(12);
        assertThat(rule.size(spec.withGrowth(10).withSize(30), 50, 4)).isEqualTo(WavePlanRule.MOST_PER_WAVE);
    }

    @Test
    @DisplayName("one kind per wave in turn, only one kind, or a seeded draw — and the same seed draws the same wave")
    void waveKinds() {
        WavePlanRule rule = new WavePlanRule();
        WaveSpec cycle = new WaveSpec("x", 6, WaveSpec.Selection.CYCLE, List.of("PILLAGER", "VINDICATOR"), 2, 0);

        assertThat(rule.creatures(cycle, 1, 1, 7)).containsOnly("PILLAGER");
        assertThat(rule.creatures(cycle, 2, 1, 7)).containsOnly("VINDICATOR");
        assertThat(rule.creatures(cycle, 3, 1, 7)).containsOnly("PILLAGER");
        assertThat(rule.creatures(cycle.withSelection(WaveSpec.Selection.ONE), 2, 1, 7)).containsOnly("PILLAGER");
        WaveSpec random = cycle.withSelection(WaveSpec.Selection.RANDOM).withSize(20);
        assertThat(rule.creatures(random, 1, 1, 7)).isEqualTo(rule.creatures(random, 1, 1, 7))
                .contains("PILLAGER", "VINDICATOR");
    }

    @Test
    @DisplayName("only fair hostile mobs are accepted; a wither or a dragon in a spec is dropped")
    void onlyAllowedMobs() {
        WaveSpec spec = new WaveSpec("x", 3, WaveSpec.Selection.RANDOM, List.of("wither", "ENDER_DRAGON", "zombie"), 2, 0);

        assertThat(spec.mobs()).containsExactly("ZOMBIE");
        assertThat(new WaveSpec("x", 3, WaveSpec.Selection.RANDOM, List.of("WITHER"), 2, 0).mobs())
                .as("nothing usable left falls back to zombies and skeletons").containsExactly("ZOMBIE", "SKELETON");
    }

    @Test
    @DisplayName("every preset is a valid spec")
    void presets() {
        assertThat(WaveSpec.PRESETS).containsKeys("warmup", "undead", "nether", "illagers", "bossrush", "endless");
        assertThat(WaveSpec.PRESETS.get("endless").isEndless()).isTrue();
        WaveSpec.PRESETS.values().forEach(spec -> assertThat(spec.mobs()).isNotEmpty());
    }

    // ── stakes ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the winner takes the pot; with a house cut that much leaves; not one unit is created or lost")
    void stakes() {
        StakeRule rule = new StakeRule();
        UUID ada = UUID.randomUUID();
        UUID bo = UUID.randomUUID();

        assertThat(rule.payouts(100, 2, List.of(ada), 0)).containsExactly(Map.entry(ada, 200L));
        assertThat(rule.payouts(100, 2, List.of(ada), 10)).containsExactly(Map.entry(ada, 180L));
        Map<UUID, Long> shared = rule.payouts(101, 3, List.of(ada, bo), 0);
        assertThat(shared.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(303);
        assertThat(rule.payouts(100, 2, List.of(), 0)).as("a draw pays nobody; it is refunded").isEmpty();
        assertThat(rule.payouts(0, 2, List.of(ada), 0)).isEmpty();
    }

    @Test
    @DisplayName("no stake can overflow the arithmetic: it is capped, and the cut is computed without overflowing")
    void hugeStakes() {
        StakeRule rule = new StakeRule();
        UUID ada = UUID.randomUUID();
        long stake = MatchSpec.duel("x").withStake(Long.MAX_VALUE).stake();

        assertThat(stake).isEqualTo(MatchSpec.MOST_STAKE);
        Map<UUID, Long> paid = rule.payouts(stake, 8, List.of(ada), 33);
        long pot = stake * 8;
        assertThat(paid.get(ada)).isEqualTo(pot - pot * 33 / 100).isPositive();
    }

    // ── sumo ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sumo: out past the ring's edge, or fallen off its floor")
    void sumo() {
        SumoRule rule = new SumoRule();

        assertThat(rule.isOut(3, 4, 0, 6)).isFalse();
        assertThat(rule.isOut(5, 4, 0, 6)).isTrue();
        assertThat(rule.isOut(0, 0, 2, 6)).isTrue();
    }

    // ── specs ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sumo always fights with the sumo kit, waves and free for all are always one round")
    void specNormalised() {
        assertThat(new MatchSpec(Mode.SUMO, "netherite", 3, 0, 1, null).kit()).isEqualTo("sumo");
        assertThat(new MatchSpec(Mode.FFA, "knight", 5, 0, 1, null).bestOf()).isEqualTo(1);
        assertThat(new MatchSpec(Mode.WAVES, "knight", 1, 0, 1, null).waves()).isNotNull();
        assertThat(new MatchSpec(Mode.DUEL, "knight", 1, 0, 1, WaveSpec.PRESETS.get("undead")).waves()).isNull();
        assertThat(Mode.parse("mobs")).contains(Mode.WAVES);
        assertThat(Mode.parse("Team")).contains(Mode.TEAMS);
        assertThat(Mode.parse("chess")).isEmpty();
    }
}
