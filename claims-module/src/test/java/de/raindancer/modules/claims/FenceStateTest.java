package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.ClaimFence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That "is the fence up?" and "are there fence blocks in the world?" cannot disagree.
 *
 * <h2>The bug this is about</h2>
 * {@code ClaimFence.enabled} was set in exactly one place — when a claim is created with auto-build on.
 * Neither {@code FenceService.build} nor {@code tearDown} touched it. So an owner who built a fence from the
 * menu got the blocks and left the flag false, the button went on reading "Not built", and the next click
 * called {@code build} again instead of taking it down. The fence could be put up and never removed.
 *
 * <p>Two things could not disagree if only one of them existed, but both have to: the flag is the owner's
 * decision, which has to survive a restart, and the blocks are the world, which is the slow part. So the fix
 * is that the code doing the world work owns the flag — one choke point, rather than every caller
 * remembering.
 *
 * <p>The world half needs a running server ({@code getWorld}, block states, region schedulers), so the
 * pairing is checked as a source scan. The alternative was no check at all, which is what let this ship.
 */
class FenceStateTest {

    private static final Path SERVICE =
            Path.of("src/main/java/de/raindancer/modules/claims/service/FenceService.java");

    private static String service() {
        try {
            return Files.readString(SERVICE);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read FenceService", unreadable);
        }
    }

    /** The body of one method, from its signature to the next method at the same indentation. */
    private static String method(String signature) {
        String body = service();
        int at = body.indexOf(signature);
        assertThat(at).as(signature + " is gone — this test is about what it does").isNotNegative();
        int next = body.indexOf("\n    public ", at + signature.length());
        return next < 0 ? body.substring(at) : body.substring(at, next);
    }

    @Test
    @DisplayName("putting the fence up records that it is up")
    void buildingSetsTheFlag() {
        assertThat(method("public FenceResult build(Claim claim, Player payer)"))
                .as("without this the blocks appear, the button still says 'Not built', and the next click "
                        + "builds again instead of taking it down")
                .contains("enabled(true)");
    }

    @Test
    @DisplayName("taking it down records that it is down")
    void tearingDownClearsTheFlag() {
        assertThat(method("public FenceResult tearDown(Claim claim, boolean refundToBank)"))
                .as("a flag left true after the blocks are gone is a fence the sync run will put back")
                .contains("enabled(false)");
    }

    @Test
    @DisplayName("the flag is what the menu and the sync run both read")
    void oneFlagAnswersForBoth() {
        // Both of these already gate on it. That is what makes leaving it stale expensive rather than
        // cosmetic: the sync run skips a fence whose flag is false and rebuilds one whose flag is true.
        String body = service();
        assertThat(body.split("claim\\.fence\\(\\)\\.enabled\\(\\)", -1))
                .as("if nothing reads the flag any more, this whole test is about a field nobody uses")
                .hasSizeGreaterThan(2);
    }

    @Test
    @DisplayName("a fresh fence is down, so a claim does not start out claiming to have one")
    void aNewFenceIsNotStanding() {
        assertThat(new ClaimFence().enabled()).isFalse();
    }

    @Test
    @DisplayName("the flag says what it was set to")
    void theFlagHolds() {
        ClaimFence fence = new ClaimFence();
        fence.enabled(true);
        assertThat(fence.enabled()).isTrue();
        fence.enabled(false);
        assertThat(fence.enabled()).isFalse();
    }

    @Test
    @DisplayName("the flag is saved where it is set, not only at the end of a successful build")
    void settingTheFlagPersistsIt() {
        // Found by review. build() can return early three ways — nothing to do, nothing affordable, the cap
        // reached — and all three are BEFORE the saveAsync at the end. So a fence turned on by an owner with
        // no material set the flag in memory and never wrote it, and the next restart read it back as off.
        String body = method("public FenceResult build(Claim claim, Player payer)");
        int flagAt = body.indexOf("enabled(true)");
        assertThat(flagAt).isNotNegative();

        assertThat(body.substring(flagAt, Math.min(body.length(), flagAt + 200)))
                .as("an unsaved flag is one that reverts on restart, and every early return below skips "
                        + "the save at the end")
                .contains("saveAsync");
    }

    @Test
    @DisplayName("the flag means intent, so turning it on twice does not write twice")
    void anAlreadyEnabledFenceIsNotResaved() {
        // A fence syncs on reshape, on chunk load and on a timer, and every one of those calls build(). If
        // the flag were written unconditionally, each would queue a save of an unchanged claim.
        assertThat(method("public FenceResult build(Claim claim, Player payer)"))
                .contains("if (!fence.enabled())");
    }
}
