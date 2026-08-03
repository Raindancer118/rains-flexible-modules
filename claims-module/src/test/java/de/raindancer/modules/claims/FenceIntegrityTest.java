package de.raindancer.modules.claims;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two ways the fence record and the world drifted apart, both found by review.
 *
 * <h2>Blocks left standing that nothing remembers</h2>
 * {@code removeColumns} skips a column whose chunk is not loaded — deliberately, so taking a fence down does
 * not drag half a world into memory. But the callback that ran afterwards forgot <em>every</em> column it was
 * asked about, loaded or not. So the blocks stayed in the world with nothing left pointing at them: not the
 * next teardown, not a resize, not an admin. Permanently orphaned, and only visible to somebody who walks
 * there.
 *
 * <h2>Tearing down while building</h2>
 * {@code build} works in three passes — region, global, region — because the cost has to be counted on one
 * thread and the blocks placed on another. {@code tearDown} is a single pass. Tear down while a build is
 * between its passes and the removal runs before the placement: the flag ends up false and the fence ends up
 * standing, which reads as the plugin refusing to take it down.
 *
 * <p>Both are held as source scans. The world half needs a running server, region schedulers and loaded
 * chunks; what is checkable without one is whether the guards are there at all, which is what was missing.
 */
class FenceIntegrityTest {

    private static final Path SERVICE =
            Path.of("src/main/java/de/raindancer/modules/claims/service/FenceService.java");

    private static String service() {
        try {
            return Files.readString(SERVICE);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read FenceService", unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the method, so a rename cannot quietly empty this")
    void theScanIsNotVacuous() {
        assertThat(service()).contains("removeColumns");
    }

    @Test
    @DisplayName("only the columns actually cleared are forgotten")
    void skippedColumnsStayOnTheBooks() {
        String body = service();
        int at = body.indexOf("private int removeColumns(");
        assertThat(at).isNotNegative();
        String method = body.substring(at);
        int ends = method.indexOf("\n    /**", 1);
        method = ends < 0 ? method : method.substring(0, ends);

        // The bug was `for (ClaimPoint point : chunkColumns) fence.remove(point)` — the list it was asked
        // about, not the list it managed to clear. Forgetting a column whose blocks are still there is what
        // orphans them, because the record is the only thing that knows where a fence block is.
        assertThat(method)
                .as("forgetting a column whose chunk was skipped leaves its blocks in the world with nothing "
                        + "pointing at them")
                .doesNotContain("for (ClaimPoint point : chunkColumns) {\n                        fence.remove");

        assertThat(method)
                .as("the cleared columns have to be collected as the work is done, so the callback can forget "
                        + "exactly those")
                .contains("cleared");
    }

    @Test
    @DisplayName("a build and a teardown of the same claim cannot interleave")
    void theTwoDirectionsAreSerialised() {
        String body = service();

        // Keyed on the claim, not a lock over the service: two owners fencing two claims at once is ordinary,
        // and one big resize should not stop everybody else's.
        assertThat(body)
                .as("without a per-claim guard, tearing down mid-build removes blocks that have not been "
                        + "placed yet and the placement then puts them back")
                .contains("Set<UUID> busy")
                .contains("busy.add(claim.id())");

        // Both directions, and before anything touches the world — a guard taken halfway through is a guard
        // that protects the second half of the work from the first half of somebody else's.
        for (String signature : new String[]{
                "public FenceResult build(Claim claim, Player payer)",
                "public FenceResult tearDown(Claim claim, boolean refundToBank)"}) {
            int at = body.indexOf(signature);
            assertThat(at).as(signature + " is gone").isNotNegative();
            String entry = body.substring(at, Math.min(body.length(), at + 900));
            assertThat(entry)
                    .as(signature + " must take the claim's turn before touching the world")
                    .contains("takeTurn(claim)");
            assertThat(entry)
                    .as(signature + " must give the turn back on every path, including a throw")
                    .contains("releaseTurn(claim)");
        }
    }

    @Test
    @DisplayName("the guard is released even when the work throws")
    void theTurnIsAlwaysGivenBack() {
        // A guard that leaks is worse than no guard: the fence becomes permanently unbuildable and the only
        // cure is a restart, with nothing in the log to say why.
        String body = service();
        int at = body.indexOf("private boolean claimTurn(");
        if (at < 0) {
            at = body.indexOf("busy");
        }
        assertThat(body)
                .as("release has to be unconditional — a finally, or a release on every exit path")
                .contains("finally");
    }

    @Test
    @DisplayName("a reshape takes the turn too, and does not ask for it twice")
    void areshapeIsSerialisedAsWell() {
        // sync() is the third door into the same race: it removes the columns that fell off the outline and
        // then puts the new ones up, so a teardown running through the middle removes blocks that are about
        // to be placed. Guarding build and tearDown alone left this one open.
        String body = service();
        int at = body.indexOf("public void sync(Claim claim, Player payer, String reason)");
        assertThat(at).as("sync() is gone").isNotNegative();
        String entry = body.substring(at, Math.min(body.length(), at + 1200));

        assertThat(entry)
                .as("a reshape does both halves of the work, so it needs the turn for both")
                .contains("takeTurn(claim)")
                .contains("releaseTurn(claim)");

        // And the trap that comes with holding it: sync builds, and build takes the turn. Going through the
        // public method would have sync refuse itself — a fence that silently never rebuilds after a resize.
        int inner = body.indexOf("private void syncNow(");
        assertThat(inner).as("the guarded body should be its own method").isNotNegative();
        String work = body.substring(inner);
        int ends = work.indexOf("\n    /**", 1);
        work = ends < 0 ? work : work.substring(0, ends);
        assertThat(work)
                .as("calling build() here would ask for a turn sync already holds, and refuse itself")
                .contains("buildNow(")
                .doesNotContain(" build(claim");
    }
}
