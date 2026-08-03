package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.store.PendingNotices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Things somebody has to be told, kept until they are there to be told.
 *
 * <h2>What this replaces</h2>
 * Two places that quietly gave up. A mute told the player why — <em>if they happened to be online</em>;
 * a closed report told the reporter what was decided — <em>if they happened to be online</em>. Both are
 * exactly the cases where they usually are not: somebody muted for spam often logs off in a huff, and a
 * report is dealt with an hour after it was filed.
 *
 * <p>Dropping the line is the worst of the three options. The player concludes nothing happened — that
 * the mute is a bug, that the report was ignored — and that conclusion is what a support channel spends
 * its evenings on. So the notice is kept and delivered on their next join.
 */
class PendingNoticesTest {

    private final UUID ayla = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();

    @Nested
    @DisplayName("keeping one")
    class Keeping {

        @Test
        @DisplayName("what is kept comes back for that player")
        void keptAndTaken(@TempDir Path folder) {
            PendingNotices waiting = new PendingNotices(folder);

            waiting.keep(ayla, "moderation.you-were-mute", Map.of("reason", "Spam"));

            assertThat(waiting.forgetAndTake(ayla)).singleElement()
                    .satisfies(notice -> {
                        assertThat(notice.key()).isEqualTo("moderation.you-were-mute");
                        assertThat(notice.values()).containsEntry("reason", "Spam");
                    });
        }

        @Test
        @DisplayName("taking them hands them over once and once only")
        void takenOnce(@TempDir Path folder) {
            // Otherwise a player is told the same thing on every join for the rest of the year.
            PendingNotices waiting = new PendingNotices(folder);
            waiting.keep(ayla, "moderation.report.was-dealt-with", Map.of("id", "R1"));

            assertThat(waiting.forgetAndTake(ayla)).hasSize(1);
            assertThat(waiting.forgetAndTake(ayla)).isEmpty();
        }

        @Test
        @DisplayName("they are kept in the order they happened")
        void inOrder(@TempDir Path folder) {
            PendingNotices waiting = new PendingNotices(folder);
            waiting.keep(ayla, "first", Map.of());
            waiting.keep(ayla, "second", Map.of());

            assertThat(waiting.forgetAndTake(ayla))
                    .extracting(PendingNotices.Notice::key)
                    .containsExactly("first", "second");
        }

        @Test
        @DisplayName("one player's notices are not another's")
        void notMixedUp(@TempDir Path folder) {
            PendingNotices waiting = new PendingNotices(folder);
            waiting.keep(ayla, "hers", Map.of());
            waiting.keep(bram, "his", Map.of());

            assertThat(waiting.forgetAndTake(ayla)).extracting(PendingNotices.Notice::key)
                    .containsExactly("hers");
            assertThat(waiting.forgetAndTake(bram)).extracting(PendingNotices.Notice::key)
                    .containsExactly("his");
        }

        @Test
        @DisplayName("nobody with nothing waiting gets an empty list rather than a null")
        void nothingWaiting(@TempDir Path folder) {
            PendingNotices waiting = new PendingNotices(folder);

            assertThat(waiting.forgetAndTake(ayla)).isEmpty();
            assertThat(waiting.forgetAndTake(null)).isEmpty();
        }

        @Test
        @DisplayName("a null player or key is ignored rather than stored")
        void nulls(@TempDir Path folder) {
            PendingNotices waiting = new PendingNotices(folder);

            waiting.keep(null, "something", Map.of());
            waiting.keep(ayla, null, Map.of());
            waiting.keep(ayla, "  ", Map.of());

            assertThat(waiting.size()).isZero();
        }

        @Test
        @DisplayName("taking the last one leaves no empty entry behind")
        void noEmptyEntries(@TempDir Path folder) {
            // The same leak the note registry avoids: a map keyed by player that keeps its empty lists
            // grows by an entry per player who was ever offline at the wrong moment.
            PendingNotices waiting = new PendingNotices(folder);
            waiting.keep(ayla, "something", Map.of());

            waiting.forgetAndTake(ayla);

            assertThat(waiting.size()).isZero();
        }

        @Test
        @DisplayName("one player cannot fill the store for ever")
        void thereIsACeiling(@TempDir Path folder) {
            // A player nobody can reach and a moderator with a macro would otherwise grow this file
            // without limit. The oldest go first: the newest news is the news worth having.
            PendingNotices waiting = new PendingNotices(folder);
            for (int i = 0; i < PendingNotices.MOST_PER_PLAYER + 10; i++) {
                waiting.keep(ayla, "notice-" + i, Map.of());
            }

            List<PendingNotices.Notice> theirs = waiting.forgetAndTake(ayla);

            assertThat(theirs).hasSize(PendingNotices.MOST_PER_PLAYER);
            assertThat(theirs.getLast().key())
                    .isEqualTo("notice-" + (PendingNotices.MOST_PER_PLAYER + 9));
        }
    }

    @Nested
    @DisplayName("across a restart")
    class Persisting {

        @Test
        @DisplayName("a notice survives being written and read")
        void aRoundTrip(@TempDir Path folder) {
            // The point of the whole class: the restart is the most likely thing to happen between a
            // mute and the muted player's next login.
            PendingNotices first = new PendingNotices(folder);
            first.keep(ayla, "moderation.you-were-mute", Map.of("reason", "Spam", "length", "1 hour"));
            first.flush();

            PendingNotices afterRestart = new PendingNotices(folder);
            afterRestart.load();

            assertThat(afterRestart.forgetAndTake(ayla)).singleElement()
                    .satisfies(notice -> {
                        assertThat(notice.key()).isEqualTo("moderation.you-were-mute");
                        assertThat(notice.values())
                                .containsEntry("reason", "Spam")
                                .containsEntry("length", "1 hour");
                    });
        }

        @Test
        @DisplayName("text with colons, quotes and newlines comes back as it was")
        void awkwardText(@TempDir Path folder) {
            PendingNotices first = new PendingNotices(folder);
            String awkward = "he said: \"it's mine\"\nand then #griefed it — <red>";
            first.keep(ayla, "moderation.report.was-dealt-with", Map.of("outcome", awkward));
            first.flush();

            PendingNotices afterRestart = new PendingNotices(folder);
            afterRestart.load();

            assertThat(afterRestart.forgetAndTake(ayla).getFirst().values())
                    .containsEntry("outcome", awkward);
        }

        @Test
        @DisplayName("nothing on disk is nothing waiting rather than a failure")
        void nothingYet(@TempDir Path folder) {
            PendingNotices waiting = new PendingNotices(folder);
            waiting.load();

            assertThat(waiting.size()).isZero();
        }

        @Test
        @DisplayName("one already delivered does not come back after a restart")
        void deliveryPersists(@TempDir Path folder) {
            PendingNotices first = new PendingNotices(folder);
            first.keep(ayla, "something", Map.of());
            first.flush();
            first.forgetAndTake(ayla);
            first.flush();

            PendingNotices afterRestart = new PendingNotices(folder);
            afterRestart.load();

            assertThat(afterRestart.forgetAndTake(ayla)).isEmpty();
        }
    }
}
