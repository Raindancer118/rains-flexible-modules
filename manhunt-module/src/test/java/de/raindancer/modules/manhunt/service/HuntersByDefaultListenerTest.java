package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who ends up on the Hunter side without asking for it — see {@link HuntersByDefaultListener} for why
 * this is not left to {@code Hunt.of} at the start whistle.
 */
class HuntersByDefaultListenerTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());

    private ManhuntTeams teams;
    private AtomicReference<ManhuntSettings> settings;
    private AtomicBoolean hunting;
    private HuntersByDefaultListener listener;

    @BeforeEach
    void setUp() {
        hunting = new AtomicBoolean();
        teams = new ManhuntTeams(hunting::get);
        settings = new AtomicReference<>(ManhuntSettings.DEFAULTS.withRunnerSelfJoin(false));
        listener = new HuntersByDefaultListener(settings::get, teams, hunting::get);
    }

    @Test
    @DisplayName("with the Runners hand-picked, somebody on no side is hunting")
    void filedUnderHunters() {
        assertThat(listener.fileUnderHunters(ANNA)).isTrue();

        assertThat(teams.isHunter(ANNA)).isTrue();
    }

    @Test
    @DisplayName("an assigned Runner keeps their side — that is the point of the setting")
    void anAssignedRunnerIsLeftAlone() {
        teams.joinRunners(ANNA);

        assertThat(listener.fileUnderHunters(ANNA)).isFalse();

        assertThat(teams.isRunner(ANNA)).isTrue();
    }

    @Test
    @DisplayName("somebody already hunting is not moved again")
    void anExistingHunterIsLeftAlone() {
        teams.joinHunters(ANNA);

        assertThat(listener.fileUnderHunters(ANNA)).isFalse();
    }

    @Test
    @DisplayName("where players pick their own side, nothing is decided for them")
    void nothingHappensWhenPlayersMayChoose() {
        settings.set(ManhuntSettings.DEFAULTS.withRunnerSelfJoin(true));

        assertThat(listener.fileUnderHunters(ANNA)).isFalse();

        assertThat(teams.everybody()).isEmpty();
    }

    @Test
    @DisplayName("nobody is filed anywhere while a hunt is being played")
    void nothingHappensMidHunt() {
        hunting.set(true);

        assertThat(listener.fileUnderHunters(ANNA)).isFalse();

        assertThat(teams.everybody()).isEmpty();
    }
}
