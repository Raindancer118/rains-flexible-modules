package de.raindancer.modules.claims;

import de.raindancer.modules.claims.store.ClaimRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Being told you have entered a claim — once, when you actually have.
 *
 * <h2>The bug this is about</h2>
 * Jumping inside a claim announced the arrival again on landing. Two things had to be wrong at once for that:
 *
 * <ul>
 *   <li><b>The quiet period defaulted to zero.</b> There is a setting for exactly this — "stops somebody
 *       pacing a border being told about it twenty times a second" — and at zero the window it guards is
 *       empty, so every re-entry announced.</li>
 *   <li><b>The vertical grace was two blocks.</b> A claim whose ceiling is the surface you are standing on
 *       already has you above it, so any jump of three blocks — a sprint jump, a jump onto a slab, a jump with
 *       any boost — left it and coming back down entered it again.</li>
 * </ul>
 *
 * <p>Both are fixed, and deliberately both: a bigger grace still leaves the flicker one geometry away, and a
 * quiet period alone would announce the first spurious crossing. Together the message means what it says.
 */
class BorderNoticeTest {

    private static final Path LISTENER =
            Path.of("src/main/java/de/raindancer/modules/claims/listener/MovementListener.java");

    private static String listener() {
        try {
            return Files.readString(LISTENER);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read MovementListener", unreadable);
        }
    }

    @Test
    @DisplayName("the notice goes to the action bar, as it did before")
    void theActionBarIsTheDefault() {
        // A border notice matters for the second it is shown and then never again. In chat it pushes real
        // conversation up the screen, and crossing a few claims on the way home leaves a wall of them.
        assertThat(ClaimSettings.DEFAULTS.enterMessageActionBar())
                .as("this is what the notices are for; chat is the fallback for somebody who wants a log")
                .isTrue();
    }

    @Test
    @DisplayName("there is a quiet period, so one crossing is one message")
    void theQuietPeriodIsNotZero() {
        assertThat(ClaimSettings.DEFAULTS.notificationCooldownSeconds())
                .as("at zero the setting guards an empty window and every re-entry announces — which is "
                        + "what made a jump look like leaving and coming back")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("a jump does not take you out of your own claim")
    void aJumpStaysInside() {
        // The case that was reported: standing on top of a claim whose ceiling is the ground you are on, so
        // you are already above it and held only by the grace. A sprint jump is three blocks of block-Y.
        assertThat(ClaimRegistry.verticalGrace())
                .as("a jump, a jump onto a slab and a jump with a boost are all a player standing still as "
                        + "far as anybody watching is concerned")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("the quiet period is measured from the last message, not the last attempt")
    void thewindowIsNotPushedForwardBySuppression() {
        // Written down because the obvious implementation is wrong in a way nobody notices: recording the
        // timestamp before deciding means every suppressed attempt renews the window, so a player standing
        // on a border and being refused twenty times a second is never told again at all — the notice does
        // not come back when they walk in properly a minute later.
        String body = listener();
        int at = body.indexOf("private boolean announceable(");
        assertThat(at).as("announceable() is gone").isNotNegative();
        String method = body.substring(at, body.indexOf("\n    }", at));

        int decides = method.indexOf("return true");
        int records = method.indexOf("lastAnnouncement.put");
        assertThat(records)
                .as("the timestamp must be written on the path that actually announces, after the decision")
                .isGreaterThan(decides);
    }

    @Test
    @DisplayName("leaving is announced the same way as arriving")
    void leavingUsesTheSameChannel() {
        String body = listener();
        int at = body.indexOf("private void onLeave(");
        assertThat(at).isNotNegative();

        assertThat(body.substring(at))
                .as("a notice that arrives in the action bar and departs in chat reads as two plugins")
                .contains("sendActionBar");
    }
}
