package de.raindancer.modules.hungergames;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.OpTrackerService;
import de.raindancer.modules.hungergames.service.OpTrackerService.EliminationOutcome;
import de.raindancer.modules.hungergames.service.RoundLogService;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.RuntimeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** {@link OpTrackerService}: deop-on-start, re-op on elimination or finish, and the restart snapshot. */
class OpTrackerServiceTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID PLAIN_TRIBUTE = UUID.randomUUID();

    private GameSession session;
    private RuntimeStore runtimeStore;
    private final Set<UUID> ops = new HashSet<>();
    /**
     * Every line the tracker sent, as "who: what".
     *
     * <p>Both halves kept, not just the text: several of these messages are only correct if they went to
     * the right person, and a list of sentences with no addressee cannot tell an admin being told they were
     * de-opped from every other admin being told the same thing.
     */
    private final List<String> notified = new ArrayList<>();
    private OpTrackerService tracker;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(0));
        runtimeStore = new RuntimeStore(dir.resolve("runtime.yml"));
        session.whitelistAdd(ADMIN, "AdminTribute");
        session.whitelistAdd(PLAIN_TRIBUTE, "PlainTribute");
        ops.add(ADMIN);

        RoundLogService roundLog = new RoundLogService(dir.resolve("logs"), uuid -> "u", id -> "t",
                mock(LogChannel.class));
        tracker = new OpTrackerService(session, opAccess(), runtimeStore, roundLog, (uuid, message) -> notified.add(uuid + ": " + message));
        tracker.settings(HungerGamesSettings.DEFAULTS);

        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
    }

    private OpTrackerService.OpAccess opAccess() {
        return new OpTrackerService.OpAccess() {
            @Override
            public boolean isOp(UUID uuid) {
                return ops.contains(uuid);
            }

            @Override
            public void setOp(UUID uuid, boolean op) {
                if (op) {
                    ops.add(uuid);
                } else {
                    ops.remove(uuid);
                }
            }
        };
    }

    @Test
    @DisplayName("opParticipants reports every tribute who currently holds OP")
    void opParticipantsReportsCurrentOps() {
        assertThat(tracker.opParticipants()).containsExactly(ADMIN);
    }

    @Test
    @DisplayName("moving to RUNNING de-ops every OP tribute, when deop-on-start is on")
    void deopsOnRunning() {
        session.transitionTo(GamePhase.RUNNING);
        tracker.onPhaseChanged(GamePhase.READY, GamePhase.RUNNING);

        assertThat(ops).doesNotContain(ADMIN);
        assertThat(tracker.deoppedAdmins()).containsExactly(ADMIN);
        assertThat(runtimeStore.loadOpSnapshot()).containsExactly(ADMIN);
        assertThat(notified).isNotEmpty();
    }

    @Test
    @DisplayName("a plain tribute who never had OP is left alone entirely")
    void plainTributeUntouched() {
        session.transitionTo(GamePhase.RUNNING);
        tracker.onPhaseChanged(GamePhase.READY, GamePhase.RUNNING);

        assertThat(tracker.deoppedAdmins()).doesNotContain(PLAIN_TRIBUTE);
    }

    @Test
    @DisplayName("deop-on-start switched off leaves every OP tribute alone")
    void doesNothingWhenSettingIsOff() {
        tracker.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "adminDeopOnStart", false));
        session.transitionTo(GamePhase.RUNNING);

        tracker.onPhaseChanged(GamePhase.READY, GamePhase.RUNNING);

        assertThat(ops).contains(ADMIN);
        assertThat(tracker.deoppedAdmins()).isEmpty();
    }

    @Test
    @DisplayName("an eliminated tribute who was never tracked here gets no outcome at all")
    void untrackedTributeGetsNoOutcome() {
        session.transitionTo(GamePhase.RUNNING);

        EliminationOutcome outcome = tracker.onEliminated(PLAIN_TRIBUTE);

        assertThat(outcome).isEqualTo(EliminationOutcome.NONE);
        assertThat(outcome.isNoop()).isTrue();
    }

    @Test
    @DisplayName("an eliminated admin is re-opped immediately, when reop-on-elimination is on")
    void reopsOnElimination() {
        session.transitionTo(GamePhase.RUNNING);
        tracker.onPhaseChanged(GamePhase.READY, GamePhase.RUNNING);
        assertThat(ops).doesNotContain(ADMIN); // de-opped for the round

        EliminationOutcome outcome = tracker.onEliminated(ADMIN);

        assertThat(ops).contains(ADMIN);
        assertThat(tracker.deoppedAdmins()).doesNotContain(ADMIN);
        assertThat(runtimeStore.loadOpSnapshot()).doesNotContain(ADMIN);
        assertThat(outcome.applyCreative())
                .isEqualTo(HungerGamesSettings.DEFAULTS.adminCreativeOnElimination());
        assertThat(outcome.teleportToCentre())
                .isEqualTo(HungerGamesSettings.DEFAULTS.adminTeleportCenterOnElimination());
    }

    @Test
    @DisplayName("an eliminated admin stays de-opped when reop-on-elimination is off, but is still tracked")
    void staysDeoppedWhenReopOnEliminationIsOff() {
        tracker.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "adminReopOnElimination", false));
        session.transitionTo(GamePhase.RUNNING);
        tracker.onPhaseChanged(GamePhase.READY, GamePhase.RUNNING);

        EliminationOutcome outcome = tracker.onEliminated(ADMIN);

        assertThat(ops).doesNotContain(ADMIN);
        assertThat(tracker.deoppedAdmins()).containsExactly(ADMIN);
        assertThat(outcome.isNoop()).isFalse();
    }

    @Test
    @DisplayName("finishing the round restores every OP still held back, when reop-on-finish is on")
    void restoresOnFinish() {
        session.transitionTo(GamePhase.RUNNING);
        tracker.onPhaseChanged(GamePhase.READY, GamePhase.RUNNING);

        session.declareTimeout(); // moves the session itself to FINISHED
        tracker.onPhaseChanged(GamePhase.RUNNING, GamePhase.FINISHED);

        assertThat(ops).contains(ADMIN);
        assertThat(tracker.deoppedAdmins()).isEmpty();
        assertThat(runtimeStore.loadOpSnapshot()).isEmpty();
    }

    @Test
    @DisplayName("finishing does nothing when reop-on-finish is off")
    void finishDoesNothingWhenSettingOff() {
        tracker.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "adminReopOnFinish", false));
        session.transitionTo(GamePhase.RUNNING);
        tracker.onPhaseChanged(GamePhase.READY, GamePhase.RUNNING);

        tracker.onPhaseChanged(GamePhase.RUNNING, GamePhase.FINISHED);

        assertThat(ops).doesNotContain(ADMIN);
        assertThat(tracker.deoppedAdmins()).containsExactly(ADMIN);
    }

    @Test
    @DisplayName("reviving a de-opped-for-the-round admin who somehow still has OP de-ops them again")
    void reviveReDeopsAnAdminWhoRegainedOp() {
        session.transitionTo(GamePhase.RUNNING);
        tracker.onPhaseChanged(GamePhase.READY, GamePhase.RUNNING);
        ops.add(ADMIN); // e.g. re-opped by an outside command while eliminated

        tracker.onRevived(ADMIN);

        assertThat(ops).doesNotContain(ADMIN);
        assertThat(tracker.deoppedAdmins()).contains(ADMIN);
    }

    @Test
    @DisplayName("the snapshot survives being reloaded after a restart")
    void restoreFromStoreReadsThePersistedSnapshot(@TempDir Path dir) {
        session.transitionTo(GamePhase.RUNNING);
        tracker.onPhaseChanged(GamePhase.READY, GamePhase.RUNNING);

        RoundLogService anotherRoundLog = new RoundLogService(dir.resolve("logs"), uuid -> "u", id -> "t",
                mock(LogChannel.class));
        OpTrackerService freshInstance = new OpTrackerService(session, opAccess(), runtimeStore,
                anotherRoundLog, (uuid, message) -> notified.add(uuid + ": " + message));

        freshInstance.restoreFromStore();

        assertThat(freshInstance.deoppedAdmins()).containsExactly(ADMIN);
    }
}
