package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.service.RoundExpiryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What happens when a round reaches its scheduled length.
 *
 * <p>The property every test here is really about is one sentence: <b>a tournament must never end because
 * nobody was looking.</b> Every branch below is a way that could happen — nobody online, nobody clicking,
 * somebody clicking twice, the settings reloading mid-question — and each one has to come out the same
 * way, which is "the round is still running".
 *
 * <p>Mockito for the two collaborators that would otherwise need a server: who is online with the right
 * permission, and putting a pair of clickable buttons in somebody's chat. The service itself is driven by
 * hand — {@code tick(elapsed)} with whatever elapsed the test wants — so no clock is involved either.
 */
@ExtendWith(MockitoExtension.class)
class RoundExpiryServiceTest {

    private static final Duration ROUND = HungerGamesSettings.DEFAULTS.roundDuration();

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BRAM = UUID.randomUUID();

    @Mock
    private GameControlService control;

    @Mock
    private RoundExpiryService.Audience audience;

    /** What was said to the log, so a round that ran long can be shown to have said why. */
    private final List<String> notes = new ArrayList<>();

    /** The buttons most recently offered, so a test can "click" one. */
    private final AtomicReference<Runnable> endButton = new AtomicReference<>();
    private final AtomicReference<Runnable> keepPlayingButton = new AtomicReference<>();
    private final List<UUID> asked = new ArrayList<>();

    private GamePhase phase = GamePhase.RUNNING;

    private RoundExpiryService service;

    private final RoundExpiryService.Prompt prompt = (who, overrun, end, extend) -> {
        asked.add(who);
        endButton.set(end);
        keepPlayingButton.set(extend);
    };

    @BeforeEach
    void setUp() {
        service = new RoundExpiryService(control, audience, prompt, notes::add, () -> phase);
    }

    private void online(UUID... deciders) {
        when(audience.whoCanDecide()).thenReturn(List.of(deciders));
    }

    @Nested
    @DisplayName("before the clock runs out")
    class StillRunning {

        @Test
        @DisplayName("nobody is asked anything")
        void nothingHappensEarly() {
            service.tick(ROUND.minusSeconds(1));

            assertThat(asked).isEmpty();
            verifyNoInteractions(control);
            verifyNoInteractions(audience);
        }

        @Test
        @DisplayName("the deadline is the configured round length")
        void theDeadlineStartsWhereItShould() {
            assertThat(service.deadline()).isEqualTo(ROUND);
        }
    }

    @Nested
    @DisplayName("when the clock runs out")
    class TheQuestion {

        @Test
        @DisplayName("everybody who could decide is asked, and the round does not end")
        void everybodyIsAsked() {
            online(ALICE, BRAM);

            service.tick(ROUND);

            assertThat(asked).containsExactly(ALICE, BRAM);
            verify(control, never()).endRound();
            assertThat(service.isWaitingForAnAnswer()).isTrue();
        }

        @Test
        @DisplayName("the question is put once, not on every tick")
        void itDoesNotNag() {
            online(ALICE);

            service.tick(ROUND);
            service.tick(ROUND.plusSeconds(1));
            service.tick(ROUND.plusSeconds(2));

            // The clock ticks every second. Without the waiting flag this is three chat messages a
            // second at the exact moment somebody is trying to read the first one.
            assertThat(asked).containsExactly(ALICE);
        }

        @Test
        @DisplayName("nothing is asked in any phase but RUNNING")
        void onlyDuringARound() {
            phase = GamePhase.FINISHED;

            service.tick(ROUND.plusMinutes(30));

            assertThat(asked).isEmpty();
            verifyNoInteractions(control);
        }
    }

    @Nested
    @DisplayName("the answers")
    class Answers {

        @Test
        @DisplayName("\"end the round\" ends it")
        void endingEndsIt() {
            online(ALICE);
            when(control.endRound()).thenReturn(true);

            service.tick(ROUND);
            endButton.get().run();

            verify(control).endRound();
            assertThat(service.isWaitingForAnAnswer()).isFalse();
            assertThat(notes).anyMatch(line -> line.contains(ALICE.toString()));
        }

        @Test
        @DisplayName("a second operator clicking end does not end it twice")
        void theFirstClickWins() {
            online(ALICE, BRAM);
            when(control.endRound()).thenReturn(true);

            service.tick(ROUND);
            Runnable bramsButton = endButton.get();   // the last one offered, which is Bram's
            bramsButton.run();
            bramsButton.run();

            // endRound() is not idempotent from the outside: the second call would run against a round
            // that has already declared a winner, and whatever it did would not be visible from here.
            verify(control, times(1)).endRound();
        }

        @Test
        @DisplayName("\"keep playing\" buys five more minutes and then asks again")
        void keepingItGoingAsksAgainLater() {
            online(ALICE);

            service.tick(ROUND);
            keepPlayingButton.get().run();

            assertThat(service.deadline()).isEqualTo(ROUND.plus(RoundExpiryService.EXTENSION));
            assertThat(service.isWaitingForAnAnswer()).isFalse();
            verify(control, never()).endRound();

            asked.clear();
            service.tick(ROUND.plus(RoundExpiryService.EXTENSION));
            assertThat(asked)
                    .as("the question has to come back, or \"keep playing\" once means the round never "
                            + "ends on time again")
                    .containsExactly(ALICE);
        }

        @Test
        @DisplayName("clicking keep-playing after somebody else ended it does nothing")
        void aLateClickIsIgnored() {
            online(ALICE, BRAM);
            when(control.endRound()).thenReturn(true);

            service.tick(ROUND);
            Runnable end = endButton.get();
            Runnable keepPlaying = keepPlayingButton.get();

            end.run();
            keepPlaying.run();

            // Otherwise a round that has already announced its winner quietly acquires a new deadline,
            // and the next tick asks whether to end a round that is over.
            assertThat(service.deadline()).isEqualTo(ROUND);
            verify(control, times(1)).endRound();
        }
    }

    @Nested
    @DisplayName("nobody there")
    class NobodyToAsk {

        @Test
        @DisplayName("an empty server extends the round rather than ending it")
        void thePlayersKeepPlaying() {
            online();   // nobody with the permission is online

            service.tick(ROUND);

            verify(control, never()).endRound();
            assertThat(service.deadline()).isEqualTo(ROUND.plus(RoundExpiryService.EXTENSION));
            assertThat(notes).anyMatch(line -> line.contains("nobody"));
        }

        @Test
        @DisplayName("and keeps extending, for as long as that takes")
        void indefinitely() {
            online();

            Duration at = ROUND;
            for (int extension = 1; extension <= 12; extension++) {
                service.tick(at);
                at = at.plus(RoundExpiryService.EXTENSION);
            }

            // An hour of nobody being around. The round is still running, which is the entire point:
            // ending it would be the plugin deciding a tournament on the strength of nobody looking.
            verify(control, never()).endRound();
            assertThat(service.deadline())
                    .isEqualTo(ROUND.plus(RoundExpiryService.EXTENSION.multipliedBy(12)));
        }
    }

    @Nested
    @DisplayName("a reload in the middle of it")
    class Reloading {

        @Test
        @DisplayName("a longer round length moves the deadline when nothing is pending")
        void aQuietReloadIsApplied() {
            service.settings(HungerGamesSettings.DEFAULTS.withGameDurationMinutes(240));

            assertThat(service.deadline()).isEqualTo(Duration.ofMinutes(240));
        }

        @Test
        @DisplayName("it does not move while somebody is being asked")
        void aReloadDoesNotChangeTheQuestionUnderneathThem() {
            online(ALICE);
            service.tick(ROUND);

            service.settings(HungerGamesSettings.DEFAULTS.withGameDurationMinutes(240));

            // Alice is looking at "the round has run its length — end it?". Silently moving the deadline
            // out by an hour makes that question false while it is still on her screen, and whichever
            // button she presses she is answering about a round that no longer exists.
            assertThat(service.deadline()).isEqualTo(ROUND);
            assertThat(service.isWaitingForAnAnswer()).isTrue();
        }
    }

    @Nested
    @DisplayName("the next round")
    class Resetting {

        @Test
        @DisplayName("forgets every extension")
        void aFreshRoundStartsFresh() {
            online();
            service.tick(ROUND);
            service.tick(ROUND.plus(RoundExpiryService.EXTENSION));
            assertThat(service.deadline()).isNotEqualTo(ROUND);

            service.reset();

            assertThat(service.deadline())
                    .as("a round that inherited the last one's extensions would be a round nobody "
                            + "configured, and it would grow every time")
                    .isEqualTo(ROUND);
            assertThat(service.isWaitingForAnAnswer()).isFalse();
        }

        @Test
        @DisplayName("and can be asked again")
        void theQuestionComesBackNextRound() {
            online(ALICE);
            when(control.endRound()).thenReturn(true);
            service.tick(ROUND);
            endButton.get().run();

            service.reset();
            asked.clear();
            service.tick(ROUND);

            assertThat(asked).containsExactly(ALICE);
        }
    }

    @Test
    @DisplayName("the overrun handed to the prompt is measured from the configured length")
    void theQuestionSaysHowLateItIs() {
        online(ALICE);
        List<Duration> overruns = new ArrayList<>();
        RoundExpiryService measuring = new RoundExpiryService(control, audience,
                (who, overrun, end, extend) -> overruns.add(overrun), notes::add, () -> phase);

        measuring.tick(ROUND.plusMinutes(3));

        // Not from the extended deadline: an operator asked for the third time wants to know the round
        // is twenty minutes long rather than that it is five minutes past the last extension.
        assertThat(overruns).containsExactly(Duration.ofMinutes(3));
    }

    @Test
    @DisplayName("a round that finished on its own while the question was out is not ended again")
    void somebodyWonInTheMeantime() {
        online(ALICE);
        when(control.endRound()).thenReturn(false);   // no longer RUNNING

        service.tick(ROUND);
        endButton.get().run();

        verify(control).endRound();
        assertThat(notes)
                .as("the operator pressed a button and something has to tell them why nothing happened")
                .anyMatch(line -> line.contains("already finished"));
    }

    @Test
    @DisplayName("the audience is asked afresh each time, not captured once")
    void whoIsOnlineIsAskedEveryTime() {
        when(audience.whoCanDecide()).thenReturn(List.of(), List.of(ALICE));

        service.tick(ROUND);                                       // nobody: extends
        service.tick(ROUND.plus(RoundExpiryService.EXTENSION));    // Alice has logged in since

        assertThat(asked)
                .as("staff arriving after the first question have to be asked the second one")
                .containsExactly(ALICE);
        verify(audience, times(2)).whoCanDecide();
        verify(control, never()).endRound();
    }

    @Test
    @DisplayName("the phase is read at tick time, not at construction")
    void aRoundThatEndsBetweenTicksIsNoticed() {
        // Deliberately nobody stubbed onto the audience: it must never be consulted at all, which is
        // what the verify at the bottom says and what Mockito's strict stubbing would flag if it were.
        phase = GamePhase.RUNNING;
        service.tick(ROUND.minusMinutes(1));
        phase = GamePhase.FINISHED;

        service.tick(ROUND);

        assertThat(asked).isEmpty();
        verify(control, never()).endRound();
        verify(audience, never()).whoCanDecide();
    }

    @Test
    @DisplayName("every eligible operator gets their own pair of buttons")
    void oneQuestionEach() {
        online(ALICE, BRAM);

        service.tick(ROUND);

        // A single shared pair would mean whoever rendered last owns the buttons, and Core's click
        // actions are registered per player anyway — a button Alice can see and only Bram can press is
        // worse than no button.
        verify(audience).whoCanDecide();
        assertThat(asked).containsExactlyInAnyOrder(ALICE, BRAM);
        assertThat(asked).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the overrun is never negative")
    void aTickExactlyOnTheDeadline() {
        online(ALICE);
        List<Duration> overruns = new ArrayList<>();
        RoundExpiryService measuring = new RoundExpiryService(control, audience,
                (who, overrun, end, extend) -> overruns.add(overrun), notes::add, () -> phase);

        measuring.tick(ROUND);

        assertThat(overruns).containsExactly(Duration.ZERO);
        assertThat(overruns.get(0).isNegative()).isFalse();
    }

    @Test
    @DisplayName("what it calls itself, for the console line listing what started")
    void itSaysWhatItIs() {
        assertThat(service.describe()).contains("round");
    }
}
