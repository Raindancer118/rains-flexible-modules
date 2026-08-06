package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.listener.AnnouncementListener;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.core.social.team.TeamId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the server is told, and — the harder half — how many times.
 *
 * <p>Every announcement here goes to everybody who is playing, so the failure that matters is not a wrong
 * word but a wrong <em>count</em>: the same line twice, or the line that should have fired and did not. Both
 * are about state rather than about text, which is why this class is mostly about the threshold set.
 */
class AnnouncementListenerTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BRAM = UUID.randomUUID();
    private static final UUID CLIO = UUID.randomUUID();

    /** Every line sent, as "key: v=…, v=…" so a test can assert on both the key and the values. */
    private final List<String> said = new ArrayList<>();

    private AnnouncementListener listener;

    private AnnouncementListener listening(HungerGamesSettings settings) {
        return new AnnouncementListener(
                (key, values) -> said.add(key + Arrays(values)),
                uuid -> {
                    if (uuid.equals(ALICE)) {
                        return "Alice";
                    }
                    return uuid.equals(BRAM) ? "Bram" : "Clio";
                },
                settings);
    }

    private static String Arrays(Object... values) {
        return values.length == 0 ? "" : " " + java.util.Arrays.toString(values);
    }

    @BeforeEach
    void setUp() {
        said.clear();
        listener = listening(HungerGamesSettings.DEFAULTS);
        // Every test below is about a round in progress, and phaseChanged is what arms the thresholds.
        listener.phaseChanged(GamePhase.READY, GamePhase.RUNNING);
    }

    @Nested
    @DisplayName("a kill")
    class Kills {

        @Test
        @DisplayName("names the victim, the killer and the killer's running total")
        void theKillfeed() {
            listener.kill(BRAM, ALICE, 3);

            assertThat(said).hasSize(1);
            assertThat(said.get(0))
                    .contains("hungergames.kill")
                    .contains("Alice")
                    .contains("Bram")
                    .as("the running total is what makes a killfeed worth reading rather than a list of "
                            + "deaths")
                    .contains("3");
        }

        @Test
        @DisplayName("says nothing when the killfeed is switched off")
        void switchedOff() {
            AnnouncementListener quiet = listening(
                    Tweak.of(HungerGamesSettings.DEFAULTS, "announceKillfeedEnabled", false));
            quiet.phaseChanged(GamePhase.READY, GamePhase.RUNNING);

            quiet.kill(BRAM, ALICE, 1);

            assertThat(said).isEmpty();
        }
    }

    @Nested
    @DisplayName("an elimination with nobody to blame")
    class Eliminations {

        @Test
        @DisplayName("is announced on its own, because no killfeed line covered it")
        void theBorderOrAFall() {
            listener.participantEliminated(ALICE, null, 7);

            assertThat(said).anyMatch(line -> line.contains("hungergames.elimination"));
        }

        @Test
        @DisplayName("is not announced twice when there was a killer")
        void theKillfeedAlreadySaidIt() {
            listener.kill(BRAM, ALICE, 1);
            said.clear();

            listener.participantEliminated(ALICE, BRAM, 7);

            // Two lines about one death is exactly what suppressing the vanilla death message was for.
            assertThat(said)
                    .noneMatch(line -> line.contains("hungergames.elimination"));
        }
    }

    @Nested
    @DisplayName("the \"only N left\" thresholds")
    class Thresholds {

        @Test
        @DisplayName("each one fires exactly once, however many times the count is reported")
        void onceEach() {
            listener.participantEliminated(ALICE, null, 3);
            listener.participantEliminated(BRAM, null, 3);
            listener.participantEliminated(CLIO, null, 3);

            // One, not three and not nine. At a count of 3 the defaults 10, 5 and 3 are all crossed at once
            // — but the wording only shows the count, so three crossings would render the same sentence
            // three times. All three are remembered; one is printed. See OneSentencePerEliminationTest.
            assertThat(said.stream().filter(line -> line.contains("remaining-players")).count())
                    .as("a threshold announced again on every subsequent elimination is a line the server "
                            + "sees over and over while people are trying to read the killfeed")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("an elimination that skips past a threshold still announces it")
        void crossedNotEqualled() {
            // Defaults are 10, 5, 3, 2. Going from 6 to 4 never *equals* 5, and the version this replaces
            // tested for equality — so a border taking two people at once announced nothing at all, which
            // is the ordinary case rather than the corner.
            listener.participantEliminated(ALICE, null, 4);

            // Something is said, and it says four. Which threshold triggered it is invisible to a player —
            // the wording interpolates the count only — so the assertion is about the count, not about the
            // threshold's number appearing in a recorded placeholder.
            assertThat(said)
                    .as("5 was crossed even though the count was never 5, and the source said nothing here")
                    .anyMatch(line -> line.contains("remaining-players") && line.contains("alive, 4"));
        }

        @Test
        @DisplayName("several thresholds crossed at once are one sentence, for the largest crossed")
        void inTheOrderTheyWerePassed() {
            listener.participantEliminated(ALICE, null, 2);

            List<String> thresholds = said.stream()
                    .filter(line -> line.contains("remaining-players"))
                    .toList();
            // Was hasSize(4) — reading the recorded placeholder values, where the four look different,
            // rather than the sentence, where they are identical. See OneSentencePerEliminationTest.
            assertThat(thresholds).hasSize(1);
            assertThat(thresholds.get(0)).contains("10");
        }

        @Test
        @DisplayName("a revive does not un-announce one")
        void noTakingItBack() {
            listener.participantEliminated(ALICE, null, 3);
            said.clear();

            listener.participantRevived(ALICE);
            listener.participantEliminated(ALICE, null, 3);

            // "Only 3 left", then "only 4 left", then "only 3 left" reads as the plugin being confused
            // rather than as an admin correcting it.
            assertThat(said).noneMatch(line -> line.contains("remaining-players"));
        }

        @Test
        @DisplayName("a fresh round announces them again")
        void thresholdsResetBetweenRounds() {
            listener.participantEliminated(ALICE, null, 3);
            said.clear();

            listener.phaseChanged(GamePhase.FINISHED, GamePhase.RUNNING);
            listener.participantEliminated(BRAM, null, 3);

            assertThat(said)
                    .as("not clearing these is how the second tournament on a server announces nothing")
                    .anyMatch(line -> line.contains("remaining-players"));
        }

        @Test
        @DisplayName("nothing is said when the feature is switched off")
        void switchedOff() {
            AnnouncementListener quiet = listening(Tweak.of(HungerGamesSettings.DEFAULTS,
                    "announceRemainingPlayersEnabled", false));
            quiet.phaseChanged(GamePhase.READY, GamePhase.RUNNING);

            quiet.participantEliminated(ALICE, null, 2);

            assertThat(said).noneMatch(line -> line.contains("remaining-players"));
        }

        @Test
        @DisplayName("a nonsense entry costs that entry and not the whole list")
        void oneBadNumber() {
            AnnouncementListener odd = listening(Tweak.of(HungerGamesSettings.DEFAULTS,
                    "announceRemainingPlayersThresholds", List.of("10", "five", "3", "-2", "")));

            assertThat(odd.configuredThresholds())
                    .as("somebody edits this list by hand; a stray word must not silence every threshold")
                    .containsExactly(10, 3);
        }
    }

    @Nested
    @DisplayName("the winner")
    class Winners {

        @Test
        @DisplayName("a solo winner is named")
        void oneTribute() {
            listener.winnerDeclared(new Winner.Solo(ALICE));

            assertThat(said).anyMatch(line -> line.contains("winner") && line.contains("Alice"));
        }

        @Test
        @DisplayName("a winning team names everybody who was on it, survivors or not")
        void aTeam() {
            listener.winnerDeclared(new Winner.Team(TeamId.fromName("red"), Set.of(ALICE, BRAM)));

            String line = said.stream().filter(said -> said.contains("winner")).findFirst().orElseThrow();
            assertThat(line)
                    .as("the ones who did not survive to see it won too, and a team victory naming only "
                            + "the survivor is the same announcement as a solo one")
                    .contains("Alice")
                    .contains("Bram")
                    .contains("red");
        }

        @Test
        @DisplayName("nobody winning is still announced")
        void everybodyDied() {
            listener.winnerDeclared(new Winner.None());

            // A round can end with everybody dead. That is a result rather than the absence of one, and a
            // round that ends in silence reads as the plugin having crashed.
            assertThat(said).anyMatch(line -> line.contains("winner"));
        }
    }

    @Test
    @DisplayName("forgetting a player says nothing and drops nothing that matters")
    void forgetIsHarmless() {
        listener.participantEliminated(ALICE, null, 3);
        int before = said.size();

        listener.forget(ALICE);

        assertThat(said).hasSize(before);
        // And crucially the thresholds are untouched — they are per round, not per person.
        said.clear();
        listener.participantEliminated(BRAM, null, 3);
        assertThat(said).noneMatch(line -> line.contains("remaining-players"));
    }
}
