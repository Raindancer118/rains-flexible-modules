package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.rules.StaffRule;
import de.raindancer.modules.moderation.store.ImmuneStaff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Remembering who may not be touched, for the times they are not here to ask.
 *
 * <h2>The hole this closes</h2>
 * A permission plugin can only answer for somebody who is <em>online</em>: ask
 * {@code server.getPlayer(uuid)} about an offline account and you get {@code null}, so every
 * permission they hold reads as absent. For most nodes that is harmless — an offline player is not
 * running commands. For {@code IMMUNE} it is the opposite of harmless, because immunity is a fact
 * about the <b>subject</b>, and the subject of a ban is very often offline.
 *
 * <p>So the version without this let any moderator ban the owner, provided they waited until the owner
 * logged off. Found by review; the comment in the code at the time claimed the opposite.
 */
class ImmuneStaffTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID moderator = UUID.randomUUID();

    @Nested
    @DisplayName("remembering")
    class Remembering {

        @Test
        @DisplayName("somebody seen holding it is remembered")
        void remembered(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);

            immune.remember(owner, true);

            assertThat(immune.isImmune(owner)).isTrue();
            assertThat(immune.isImmune(moderator)).isFalse();
            assertThat(immune.isImmune(null)).isFalse();
        }

        @Test
        @DisplayName("somebody who has lost it is forgotten again")
        void forgotten(@TempDir Path folder) {
            // Otherwise demoting somebody leaves them permanently untouchable, which is the failure
            // in the other direction and just as hard to explain.
            ImmuneStaff immune = new ImmuneStaff(folder);
            immune.remember(owner, true);

            immune.remember(owner, false);

            assertThat(immune.isImmune(owner)).isFalse();
        }

        @Test
        @DisplayName("remembering the same person twice does not double anything")
        void idempotent(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);

            immune.remember(owner, true);
            immune.remember(owner, true);

            assertThat(immune.size()).isOne();
        }

        @Test
        @DisplayName("a null id is ignored rather than stored")
        void nulls(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);

            immune.remember(null, true);

            assertThat(immune.size()).isZero();
        }
    }

    @Nested
    @DisplayName("across a restart")
    class Persisting {

        @Test
        @DisplayName("who is immune survives being written and read")
        void aRoundTrip(@TempDir Path folder) {
            // Without this, the first ban after a restart can land on an offline owner — the window
            // is exactly as long as it takes them to log in once.
            ImmuneStaff first = new ImmuneStaff(folder);
            first.remember(owner, true);
            first.flush();

            ImmuneStaff afterRestart = new ImmuneStaff(folder);
            afterRestart.load();

            assertThat(afterRestart.isImmune(owner)).isTrue();
        }

        @Test
        @DisplayName("nothing on disk is nobody immune rather than a failure")
        void nothingYet(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);
            immune.load();

            assertThat(immune.size()).isZero();
        }

        @Test
        @DisplayName("somebody who lost it does not come back after a restart")
        void removalPersists(@TempDir Path folder) {
            ImmuneStaff first = new ImmuneStaff(folder);
            first.remember(owner, true);
            first.flush();
            first.remember(owner, false);
            first.flush();

            ImmuneStaff afterRestart = new ImmuneStaff(folder);
            afterRestart.load();

            assertThat(afterRestart.isImmune(owner)).isFalse();
        }
    }

    @Nested
    @DisplayName("what the rule then does")
    class TheRule {

        /** Permissions as a live server answers them: nothing at all for anybody offline. */
        private StaffRule ruleWhere(ImmuneStaff immune, Set<UUID> online) {
            return new StaffRule((who, node) -> {
                if (who == null) {
                    return true;                        // the console
                }
                if (online.contains(who)) {
                    return true;                        // online, and holds everything, for this test
                }
                // Offline: a permission plugin cannot answer, so only what is remembered is known.
                return StaffRule.IMMUNE.equals(node) && immune.isImmune(who);
            });
        }

        @Test
        @DisplayName("an offline immune account is still protected")
        void theHoleIsClosed(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);
            immune.remember(owner, true);
            StaffRule rule = ruleWhere(immune, new HashSet<>(Set.of(moderator)));

            assertThat(rule.isImmune(owner)).isTrue();
            assertThat(rule.canAct(moderator, owner, ModerationPermission.BAN).refusal())
                    .contains(StaffRule.THEY_ARE_IMMUNE);
        }

        @Test
        @DisplayName("an ordinary offline player can still be banned, which is the point of the command")
        void ordinaryPlayersAreStillReachable(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);
            UUID somebody = UUID.randomUUID();
            StaffRule rule = ruleWhere(immune, new HashSet<>(Set.of(moderator)));

            assertThat(rule.canAct(moderator, somebody, ModerationPermission.BAN).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("the console still reaches an offline immune account")
        void theConsoleIsUnaffected(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);
            immune.remember(owner, true);
            StaffRule rule = ruleWhere(immune, new HashSet<>());

            assertThat(rule.canAct(null, owner, ModerationPermission.BAN).isAllowed()).isTrue();
        }
    }
}
