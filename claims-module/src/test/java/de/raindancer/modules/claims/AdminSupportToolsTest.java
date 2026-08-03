package de.raindancer.modules.claims;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three admin commands that answer a support question rather than change something.
 *
 * <h2>Why these three specifically</h2>
 * The rewrite kept the admin commands that <em>do</em> things and lost the ones that <em>explain</em> things,
 * which is the wrong way round to lose any: an admin can always click a claim into the shape they want, but
 * without a diagnostic they are guessing about why it behaves as it does.
 *
 * <ul>
 *   <li><b>why</b> — for the player standing here, every flag with its policy, the claim's override, the
 *       audience they fall in and the verdict. This was the best support tool the old plugin had. It turns
 *       "protection is broken" into a line an admin can read, and its absence turns every report into an
 *       afternoon. Flags moved into RainsCore in the rewrite, which makes the question harder to answer by
 *       reading config, not easier.</li>
 *   <li><b>stick &lt;player&gt;</b> — hands the marking-out tool to somebody else. The one way to help a
 *       player who cannot work out the selection tool is to give them one and watch.</li>
 *   <li><b>save</b> — flushes to disk now. Everything else here relies on the autosave interval, so
 *       "let me save before I try something" had no answer.</li>
 * </ul>
 *
 * <p>Held as a source scan: all three end in a message to a player or a write to disk, and the assertion worth
 * making is that they are reachable and gated. What they print needs a server to see.
 */
class AdminSupportToolsTest {

    private static final Path ADMIN =
            Path.of("src/main/java/de/raindancer/modules/claims/command/ClaimAdminCommand.java");

    private static String source() {
        try {
            return Files.readString(ADMIN);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read ClaimAdminCommand", unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the command, so a rename cannot quietly empty this")
    void theScanIsNotVacuous() {
        assertThat(source()).contains("case \"bypass\"");
    }

    @Test
    @DisplayName("all three are reachable as subcommands")
    void theyAreWiredUp() {
        String body = source();
        List<String> missing = new ArrayList<>();
        for (String word : List.of("why", "stick", "save")) {
            if (!body.contains("\"" + word + "\"")) {
                missing.add(word);
            }
        }
        assertThat(missing)
                .as("an admin tool that exists and cannot be typed is one nobody will find")
                .isEmpty();
    }

    @Test
    @DisplayName("the diagnostic reports policy, audience and verdict — not just the answer")
    void theDumpExplainsRatherThanAsserts() {
        String body = source();
        int at = body.indexOf("private void why(");
        assertThat(at).as("there is no why() at all").isNotNegative();
        String method = body.substring(at, Math.min(body.length(), at + 3000));

        // "PvP: denied" is not a diagnostic; it is the thing the admin already knows. The four parts that
        // make it an answer are the server's policy, the claim's own override, which audience the player
        // falls in, and only then the verdict.
        assertThat(method)
                .as("without the policy an admin cannot tell a server rule from an owner's choice")
                .contains("policy(");
        assertThat(method)
                .as("without the audience the answer is unattributable — the same flag differs per group")
                .contains("audienceOf(");
        assertThat(method).contains("isAllowed");
    }

    @Test
    @DisplayName("every one of them is an admin's alone")
    void theyAreGated() {
        String body = source();
        List<String> ungated = new ArrayList<>();
        for (String name : List.of("private void why(", "private void giveStick(", "private void save(")) {
            int at = body.indexOf(name);
            if (at < 0) {
                ungated.add(name + " is missing");
                continue;
            }
            String method = body.substring(at, Math.min(body.length(), at + 2500));
            if (!method.contains("isServerAdmin")) {
                ungated.add(name);
            }
        }
        assertThat(ungated)
                .as("these read other people's claims and write the server's files; the permission node on "
                        + "the command is not enough on its own, since these are also reachable in code")
                .isEmpty();
    }

    @Test
    @DisplayName("handing somebody a stick tells them it happened")
    void theRecipientIsTold() {
        int at = source().indexOf("private void giveStick(");
        assertThat(at).isNotNegative();

        assertThat(source().substring(at, Math.min(source().length(), at + 1800)))
                .as("an item appearing in your inventory with no explanation is indistinguishable from a bug")
                .contains("messages().send");
    }
}
