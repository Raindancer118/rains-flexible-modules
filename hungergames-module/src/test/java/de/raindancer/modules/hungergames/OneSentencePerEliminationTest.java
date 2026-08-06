package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.listener.AnnouncementListener;
import de.raindancer.modules.hungergames.model.GamePhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One elimination says one sentence about how many are left, and says nothing when nobody is.
 *
 * <h2>What a solo test round actually printed</h2>
 * <pre>
 * Hunger Games » ☠ Raindancer118 is out. (0 tributes left)
 * Hunger Games » Only 0 tributes still alive!
 * Hunger Games » Only 0 tributes still alive!
 * Hunger Games » Only 0 tributes still alive!
 * Hunger Games » Only 0 tributes still alive!
 * Hunger Games » ♛ VICTOR OF THE HUNGER GAMES: nobody
 * </pre>
 *
 * <h2>Both halves of that were mine, and the second one was a regression</h2>
 * The source announced a threshold on <b>equality</b> — {@code if (remaining == threshold && announced.add(
 * threshold))} — so it printed one line, and never at zero, because zero is not a configured threshold.
 *
 * <p>I changed it to "crossed rather than equalled", for a real reason: with thresholds of 10, 5, 3, 2, an
 * elimination taking the count from 6 to 4 equals none of them, so the source announced <em>nothing</em> when
 * the border took two people at once — which is the ordinary case, not the corner. That part was right and
 * stays.
 *
 * <p>What I did not think through is that the sentence only interpolates the <em>count</em>. The threshold that
 * triggered it appears nowhere in the wording. So an elimination crossing four thresholds renders four
 * <em>identical</em> lines, and my own test asserted {@code hasSize(4)} — reading the recorded placeholder
 * values, where the four look different, rather than the sentence, where they cannot. A test can confirm a
 * design and still be looking at the wrong thing.
 *
 * <p>So: crossings are still all remembered, one sentence is printed, and a count below one prints none — the
 * winner announcement is what says the round is over, and "only 0 still alive" is not information.
 */
class OneSentencePerEliminationTest {

    private static final UUID ALICE = UUID.randomUUID();

    /** Every announcement, as "key alive=N" — the count is what the sentence actually shows. */
    private final List<String> said = new ArrayList<>();

    private AnnouncementListener listener;

    @BeforeEach
    void setUp() {
        listener = listening(HungerGamesSettings.DEFAULTS);
        listener.phaseChanged(GamePhase.READY, GamePhase.RUNNING);
    }

    private AnnouncementListener listening(HungerGamesSettings settings) {
        return new AnnouncementListener((key, values) -> {
            StringBuilder line = new StringBuilder(key);
            for (int at = 0; at + 1 < values.length; at += 2) {
                line.append(' ').append(values[at]).append('=').append(values[at + 1]);
            }
            said.add(line.toString());
        }, uuid -> "somebody", settings);
    }

    @Test
    @DisplayName("four thresholds crossed at once is one sentence, not four identical ones")
    void oneSentence() {
        // A solo round: the single tribute dies, the count goes to zero, and every default threshold
        // (10, 5, 3, 2) is crossed in the same instant.
        listener.participantEliminated(ALICE, null, 2);

        assertThat(remaining())
                .as("the wording only shows the count, so a line per crossed threshold is the same sentence "
                        + "printed again — four times, in front of everybody")
                .hasSize(1);
        assertThat(remaining().get(0)).contains("alive=2");
    }

    @Test
    @DisplayName("nothing is said when nobody is left")
    void nothingAtZero() {
        listener.participantEliminated(ALICE, null, 0);

        assertThat(remaining())
                .as("'Only 0 tributes still alive' is not information; the winner announcement is what says "
                        + "the round is over")
                .isEmpty();
    }

    @Test
    @DisplayName("a threshold crossed rather than equalled is still announced — that part was a real fix")
    void crossingStillCounts() {
        // 6 → 4 equals none of 10, 5, 3, 2. The source announced nothing here.
        listener.participantEliminated(ALICE, null, 4);

        assertThat(remaining()).hasSize(1);
        assertThat(remaining().get(0)).contains("alive=4");
    }

    @Test
    @DisplayName("thresholds crossed in one go are not announced again later")
    void crossingsAreRemembered() {
        listener.participantEliminated(ALICE, null, 2);
        said.clear();

        // 10, 5, 3 and 2 were all crossed a moment ago. Only remembering the one that was printed would say
        // "only 1 left" again here for each of the others.
        listener.participantEliminated(ALICE, null, 1);

        assertThat(remaining()).isEmpty();
    }

    private List<String> remaining() {
        return said.stream().filter(line -> line.contains("remaining-players")).toList();
    }
}
