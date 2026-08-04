package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.rules.StaffRule;
import de.raindancer.modules.moderation.store.ImmuneStaff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list of accounts a moderator may not touch.
 *
 * <h2>Why this is a written list and not a permission</h2>
 * A permission plugin can only answer for somebody who is <em>online</em>: ask
 * {@code server.getPlayer(uuid)} about an offline account and you get {@code null}, so every permission
 * they hold reads as absent. For most nodes that is harmless — an offline player is not running
 * commands. For protection it is the opposite of harmless, because it is a fact about the
 * <b>subject</b>, and the subject of a ban is very often offline.
 *
 * <p>The first version had no list at all, and any moderator could ban the owner by waiting until the
 * owner logged off. The second kept the permission and cached it here whenever its holder logged in,
 * which left the protection as old as their last login and made a grant to somebody away do nothing.
 * This is the third: the list <em>is</em> the fact, written only by {@code /protect} at the console.
 */
class ImmuneStaffTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID moderator = UUID.randomUUID();

    @Nested
    @DisplayName("protecting")
    class Protecting {

        @Test
        @DisplayName("a protected account is protected, and nobody else is")
        void protectedAccount(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);

            assertThat(immune.protect(owner)).isTrue();

            assertThat(immune.isImmune(owner)).isTrue();
            assertThat(immune.isImmune(moderator)).isFalse();
            assertThat(immune.isImmune(null)).isFalse();
        }

        @Test
        @DisplayName("unprotecting takes it off again")
        void unprotecting(@TempDir Path folder) {
            // The half that has to exist. A protection nothing can lift outlives the person leaving the
            // staff, and then the only way to act on that account is a text editor and a restart.
            ImmuneStaff immune = new ImmuneStaff(folder);
            immune.protect(owner);

            assertThat(immune.unprotect(owner)).isTrue();

            assertThat(immune.isImmune(owner)).isFalse();
        }

        @Test
        @DisplayName("each answers whether it changed anything, so nothing is written for nothing")
        void idempotent(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);

            assertThat(immune.protect(owner)).isTrue();
            assertThat(immune.protect(owner)).as("already protected").isFalse();
            assertThat(immune.size()).isOne();

            assertThat(immune.unprotect(owner)).isTrue();
            assertThat(immune.unprotect(owner)).as("was not protected").isFalse();
        }

        @Test
        @DisplayName("a null id is ignored rather than stored")
        void nulls(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);

            assertThat(immune.protect(null)).isFalse();
            assertThat(immune.unprotect(null)).isFalse();

            assertThat(immune.size()).isZero();
        }

        @Test
        @DisplayName("the list can be read back, for the console command that shows it")
        void listing(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);
            immune.protect(owner);
            immune.protect(moderator);

            assertThat(immune.all()).containsExactlyInAnyOrder(owner, moderator);
            assertThat(immune.all()).isUnmodifiable();
        }
    }

    @Nested
    @DisplayName("across a restart")
    class Persisting {

        @Test
        @DisplayName("who is protected survives being written and read")
        void aRoundTrip(@TempDir Path folder) {
            // Nothing refills this list any more — no login mirrors a permission into it — so losing it
            // is losing the protection outright rather than until everybody has logged in once.
            ImmuneStaff first = new ImmuneStaff(folder);
            first.protect(owner);
            first.flush();

            ImmuneStaff afterRestart = new ImmuneStaff(folder);
            afterRestart.load();

            assertThat(afterRestart.isImmune(owner)).isTrue();
        }

        @Test
        @DisplayName("nothing on disk is nobody protected rather than a failure")
        void nothingYet(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);
            immune.load();

            assertThat(immune.size()).isZero();
        }

        @Test
        @DisplayName("somebody unprotected does not come back after a restart")
        void removalPersists(@TempDir Path folder) {
            ImmuneStaff first = new ImmuneStaff(folder);
            first.protect(owner);
            first.flush();
            first.unprotect(owner);
            first.flush();

            ImmuneStaff afterRestart = new ImmuneStaff(folder);
            afterRestart.load();

            assertThat(afterRestart.isImmune(owner)).isFalse();
        }
    }

    @Nested
    @DisplayName("what the rule then does")
    class TheRule {

        /**
         * The rule as the module wires it: permissions only for whoever is online, and protection from
         * the list plus the operators — neither of which needs the subject to be here.
         */
        private StaffRule ruleWhere(ImmuneStaff immune, Set<UUID> online, Set<UUID> operators) {
            return new StaffRule(
                    (who, node) -> who == null || online.contains(who),
                    subject -> subject != null
                            && (immune.isImmune(subject) || operators.contains(subject)));
        }

        @Test
        @DisplayName("an offline protected account is still protected")
        void theHoleIsClosed(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);
            immune.protect(owner);
            StaffRule rule = ruleWhere(immune, Set.of(moderator), Set.of());

            assertThat(rule.isImmune(owner)).isTrue();
            assertThat(rule.canAct(moderator, owner, ModerationPermission.BAN).refusal())
                    .contains(StaffRule.THEY_ARE_IMMUNE);
        }

        @Test
        @DisplayName("an operator is protected without anybody having typed /protect")
        void operatorsAreCoveredOnTheirOwn(@TempDir Path folder) {
            // The window this closes: on a fresh server, before anybody has run /protect, an admin
            // would otherwise be able to ban the owner.
            ImmuneStaff immune = new ImmuneStaff(folder);
            StaffRule rule = ruleWhere(immune, Set.of(moderator), Set.of(owner));

            assertThat(immune.isImmune(owner)).as("not on the list, and does not need to be").isFalse();
            assertThat(rule.canAct(moderator, owner, ModerationPermission.BAN).refusal())
                    .contains(StaffRule.THEY_ARE_IMMUNE);
        }

        @Test
        @DisplayName("an ordinary offline player can still be banned, which is the point of the command")
        void ordinaryPlayersAreStillReachable(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);
            UUID somebody = UUID.randomUUID();
            StaffRule rule = ruleWhere(immune, Set.of(moderator), Set.of());

            assertThat(rule.canAct(moderator, somebody, ModerationPermission.BAN).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("the console still reaches an offline protected account")
        void theConsoleIsUnaffected(@TempDir Path folder) {
            ImmuneStaff immune = new ImmuneStaff(folder);
            immune.protect(owner);
            StaffRule rule = ruleWhere(immune, Set.of(), Set.of());

            assertThat(rule.canAct(null, owner, ModerationPermission.BAN).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("holding every permission in the game does not protect anybody")
        void permissionsNoLongerProtect(@TempDir Path folder) {
            // The whole change: protection used to be a node, so anybody who could grant nodes could
            // grant it. Here the subject is online and holds everything, and is still bannable.
            ImmuneStaff immune = new ImmuneStaff(folder);
            StaffRule rule = ruleWhere(immune, Set.of(moderator, owner), Set.of());

            assertThat(rule.canAct(moderator, owner, ModerationPermission.BAN).isAllowed()).isTrue();
        }
    }
}
