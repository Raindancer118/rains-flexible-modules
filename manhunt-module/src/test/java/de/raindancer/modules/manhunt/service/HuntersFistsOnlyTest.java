package de.raindancer.modules.manhunt.service;

import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Verdict;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Hunters only punch each other")
class HuntersFistsOnlyTest {

    private static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    private static final UUID HUNTER_A = UUID.nameUUIDFromBytes("a".getBytes());
    private static final UUID HUNTER_B = UUID.nameUUIDFromBytes("b".getBytes());
    private static final UUID OUTSIDER = UUID.nameUUIDFromBytes("outsider".getBytes());

    private final AtomicReference<Hunt> live = new AtomicReference<>(
            Hunt.of(Set.of(RUNNER, HUNTER_A, HUNTER_B), Set.of(RUNNER)));
    private final AtomicReference<ManhuntSettings> settings =
            new AtomicReference<>(ManhuntSettings.DEFAULTS.withHuntersFistsOnly(true));
    private final HuntersFistsOnly rule =
            new HuntersFistsOnly(() -> Optional.ofNullable(live.get()), settings::get);

    private static Attack hit(UUID attacker, UUID victim, Attack.Means means) {
        return Attack.between(attacker, victim, "hunt").withMeans(means);
    }

    @Test
    @DisplayName("a Hunter's fist on another Hunter is allowed — no opinion, the server's rules apply")
    void fistIsFine() {
        assertThat(rule.apply(hit(HUNTER_A, HUNTER_B, Attack.Means.BARE_HANDED))).isNull();
    }

    @Test
    @DisplayName("a sword, a bow, a pet, or anything Core could not name is refused between Hunters")
    void everythingElseRefused() {
        for (Attack.Means means : new Attack.Means[] {Attack.Means.HELD_ITEM, Attack.Means.RANGED,
                Attack.Means.OTHER, Attack.Means.UNKNOWN}) {
            assertThat(rule.apply(hit(HUNTER_A, HUNTER_B, means))).as(means.name())
                    .isEqualTo(Verdict.FISTS_ONLY);
        }
    }

    @Test
    @DisplayName("Hunters against the Runner are untouched — the rule is about teammates")
    void runnerFightsAreUntouched() {
        assertThat(rule.apply(hit(HUNTER_A, RUNNER, Attack.Means.HELD_ITEM))).isNull();
        assertThat(rule.apply(hit(RUNNER, HUNTER_A, Attack.Means.RANGED))).isNull();
    }

    @Test
    @DisplayName("somebody outside the hunt is not a Hunter")
    void outsiders() {
        assertThat(rule.apply(hit(OUTSIDER, HUNTER_A, Attack.Means.HELD_ITEM))).isNull();
    }

    @Test
    @DisplayName("off, or between hunts, it has no opinion at all")
    void offOrIdle() {
        settings.set(ManhuntSettings.DEFAULTS);
        assertThat(rule.apply(hit(HUNTER_A, HUNTER_B, Attack.Means.HELD_ITEM))).isNull();

        settings.set(ManhuntSettings.DEFAULTS.withHuntersFistsOnly(true));
        live.set(null);
        assertThat(rule.apply(hit(HUNTER_A, HUNTER_B, Attack.Means.HELD_ITEM))).isNull();
    }

    @Test
    @DisplayName("hurting yourself is never refused")
    void self() {
        assertThat(rule.apply(hit(HUNTER_A, HUNTER_A, Attack.Means.RANGED))).isNull();
    }
}
