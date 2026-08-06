package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.command.RoundCommand;
import de.raindancer.modules.hungergames.service.GameControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many tributes {@code /init} builds an arena for, and how it finds out.
 *
 * <h2>Two things this was written for, both of them regressions I introduced</h2>
 * The old plugin asked in chat: run {@code /init}, type a number, and the arena is built for that many. The
 * port quietly replaced that with "however many are on the whitelist right now" — and on a server where the
 * whitelist had not been filled in yet, that is two. The gamemaster who ran it saw "Building an arena for 2
 * tributes" and had never been asked. Deriving it looked tidier and was a decision nobody made: the register
 * is who <em>may</em> play, and the arena size is how many platforms to paste, which is a thing a gamemaster
 * knows before the sign-up sheet is finished.
 *
 * <p>The second was worse to read. {@code /init} printed "✓ /init done" and then, a moment later, "The arena
 * could not be built". Both were true from where they were written — the command reported that the job had
 * been accepted, and the build reported that it had failed — and together they are nonsense. A step whose
 * outcome arrives later must not be announced as finished by the thing that started it.
 */
class InitAsksHowManyTest {

    @Nested
    @DisplayName("where the number comes from")
    class TheCount {

        @Test
        @DisplayName("a number typed after the command is the number used")
        void anArgumentIsUsed() {
            assertThat(RoundCommand.countIn(new String[] {"24"})).contains(24);
        }

        @Test
        @DisplayName("nothing typed means nothing assumed")
        void noArgumentIsNoAnswer() {
            // Empty rather than a default. The whole bug was assuming an answer nobody gave.
            assertThat(RoundCommand.countIn(new String[0])).isEmpty();
            assertThat(RoundCommand.countIn(new String[] {""})).isEmpty();
        }

        @Test
        @DisplayName("something that is not a number is not a number")
        void rubbishIsRefused() {
            assertThat(RoundCommand.countIn(new String[] {"lots"})).isEmpty();
            assertThat(RoundCommand.countIn(new String[] {"12x"})).isEmpty();
        }

        @Test
        @DisplayName("a count outside what a round can hold is refused rather than clamped")
        void theBoundsAreTheBounds() {
            // Clamping would build an arena for a number the gamemaster did not choose — the same class of
            // mistake as deriving one. GameControlService refuses it and says the range.
            // One is a legitimate arena — an admin testing alone. See ASoloRoundIsTestableTest.
            assertThat(RoundCommand.countIn(new String[] {"1"})).contains(1);
            assertThat(RoundCommand.countIn(new String[] {"0"})).isEmpty();
            assertThat(RoundCommand.countIn(new String[] {"101"})).isEmpty();

            assertThat(RoundCommand.countIn(new String[] {String.valueOf(GameControlService.MIN_PLAYERS)}))
                    .contains(GameControlService.MIN_PLAYERS);
            assertThat(RoundCommand.countIn(new String[] {String.valueOf(GameControlService.MAX_PLAYERS)}))
                    .contains(GameControlService.MAX_PLAYERS);
        }

        @Test
        @DisplayName("a negative number cannot slip through as a count")
        void negativesAreRefused() {
            assertThat(RoundCommand.countIn(new String[] {"-5"})).isEmpty();
        }
    }

    @Nested
    @DisplayName("what is said, and when")
    class Reporting {

        @Test
        @DisplayName("a step that reports itself is not also announced as done")
        void nothingIsAnnouncedTwice() {
            // This is the "✓ done" followed by "could not be built" that a gamemaster actually saw. /init
            // starts a build that finishes on another tick, so the command cannot know the outcome — and must
            // not claim one.
            assertThat(RoundCommand.init(() -> null).reportsItsOwnOutcome())
                    .as("/init's outcome arrives through ArenaBuildService.Told, on the tick the blocks are "
                            + "placed")
                    .isTrue();
        }

        @Test
        @DisplayName("a step that finishes while the command runs still says so")
        void theOthersStillReport() {
            // /startup and /start are not silent: they finish, or refuse, within the call.
            assertThat(RoundCommand.startup(() -> null).reportsItsOwnOutcome()).isFalse();
            assertThat(RoundCommand.start(() -> null).reportsItsOwnOutcome()).isFalse();
        }

        @Test
        @DisplayName("a refusal is always spoken, whoever reports the success")
        void refusalsAreNeverSwallowed() {
            // The one thing that must be true of every step: a gamemaster who typed something that could not
            // run finds out. A silent refusal is retyped, and the second attempt is made while they are
            // already wondering whether the plugin is broken.
            for (RoundCommand step : RoundCommand.theRunUp(() -> null)) {
                assertThat(step.saysWhyItRefused())
                        .as("/%s", step.verb())
                        .isTrue();
            }
        }
    }
}
