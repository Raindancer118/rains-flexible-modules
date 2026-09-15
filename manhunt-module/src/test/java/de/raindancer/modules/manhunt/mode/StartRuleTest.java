package de.raindancer.modules.manhunt.mode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What a hunt needs before anybody is frozen for a countdown. */
class StartRuleTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());

    @Test
    @DisplayName("one Runner and somebody chasing them is a hunt")
    void enough() {
        assertThat(StartRule.refuse(true, Set.of(ANNA, BEN), Set.of(ANNA))).isEmpty();
    }

    @Test
    @DisplayName("nobody on the Runner side is refused by name")
    void noRunner() {
        assertThat(StartRule.refuse(true, Set.of(ANNA, BEN), Set.of())).contains(StartRule.NO_RUNNER);
    }

    @Test
    @DisplayName("a Runner who is not actually racing does not count as one")
    void runnerNotPresent() {
        UUID somebodyElse = UUID.nameUUIDFromBytes("caro".getBytes());

        assertThat(StartRule.refuse(true, Set.of(ANNA, BEN), Set.of(somebodyElse)))
                .contains(StartRule.NO_RUNNER);
    }

    @Test
    @DisplayName("everybody on the Runner side leaves nobody to chase them")
    void noHunter() {
        assertThat(StartRule.refuse(true, Set.of(ANNA, BEN), Set.of(ANNA, BEN)))
                .contains(StartRule.NO_HUNTER);
    }

    @Test
    @DisplayName("without a goal the Runners could never win, whoever is on which side")
    void noGoal() {
        assertThat(StartRule.refuse(false, Set.of(ANNA, BEN), Set.of(ANNA))).contains(StartRule.NO_GOAL);
    }

    @Test
    @DisplayName("an empty lobby is refused rather than asked about sides")
    void nobodyAtAll() {
        assertThat(StartRule.refuse(true, Set.of(), Set.of(ANNA))).contains(StartRule.NO_RUNNER);
    }
}
