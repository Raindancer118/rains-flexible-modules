package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.rules.StaffRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may do what to whom.
 *
 * <h2>Why the rule takes ids rather than a CommandSender</h2>
 * So it can be asked without a server. Every one of these questions is one a <em>screen</em> asks to
 * decide whether to grey a button, several times per render, and a rule that needed a running Paper to
 * answer would be a rule nobody tested and every screen worked around.
 *
 * <p>The permission lookup arrives as {@link StaffRule.Rights} — one method, backed by
 * {@code Player#hasPermission} at runtime and by a map here.
 */
class StaffRuleTest {

    /** A permission table. The whole reason the rule takes an interface rather than a Player. */
    private static final class Table implements StaffRule.Rights {

        private final Map<UUID, Set<String>> nodes = new HashMap<>();

        Table give(UUID who, String node) {
            nodes.computeIfAbsent(who, key -> new HashSet<>()).add(node);
            return this;
        }

        @Override
        public boolean has(UUID who, String node) {
            return who != null && nodes.getOrDefault(who, Set.of()).contains(node);
        }
    }

    private final UUID moderator = UUID.randomUUID();
    private final UUID helper = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    private StaffRule ruleWhere(Table table) {
        return new StaffRule(table);
    }

    @Nested
    @DisplayName("holding a permission")
    class Permissions {

        @Test
        @DisplayName("a moderator may do what their node says and nothing else")
        void nodesAreSeparate() {
            // Split on purpose: a helper who can mute should not thereby be able to ban.
            StaffRule rule = ruleWhere(new Table().give(helper, ModerationPermission.MUTE.node()));

            assertThat(rule.may(helper, ModerationPermission.MUTE)).isTrue();
            assertThat(rule.may(helper, ModerationPermission.BAN)).isFalse();
        }

        @Test
        @DisplayName("the console may do everything, because it is the person who owns the machine")
        void theConsoleMayDoAnything() {
            StaffRule rule = ruleWhere(new Table());

            for (ModerationPermission what : ModerationPermission.values()) {
                assertThat(rule.may(null, what))
                        .as("the console should be allowed %s", what)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("somebody with nothing may do nothing")
        void nothingIsTheDefault() {
            StaffRule rule = ruleWhere(new Table());

            for (ModerationPermission what : ModerationPermission.values()) {
                assertThat(rule.may(player, what)).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("acting on somebody")
    class Acting {

        @Test
        @DisplayName("a moderator with the permission may act on an ordinary player")
        void theOrdinaryCase() {
            StaffRule rule = ruleWhere(new Table().give(moderator, ModerationPermission.BAN.node()));

            assertThat(rule.canAct(moderator, player, ModerationPermission.BAN).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("without the permission it is refused, and the refusal names why")
        void withoutThePermission() {
            StaffRule rule = ruleWhere(new Table());

            assertThat(rule.canAct(moderator, player, ModerationPermission.BAN).refusal())
                    .contains(StaffRule.NO_PERMISSION);
        }

        @Test
        @DisplayName("nobody punishes themselves")
        void notYourself() {
            // Not politeness: a moderator who bans themselves is a moderator who cannot come back and
            // lift it, which has happened on a real server and needed a database edit to undo.
            StaffRule rule = ruleWhere(new Table().give(moderator, ModerationPermission.BAN.node()));

            assertThat(rule.canAct(moderator, moderator, ModerationPermission.BAN).refusal())
                    .contains(StaffRule.NOT_YOURSELF);
        }

        @Test
        @DisplayName("somebody immune is not punished by a moderator")
        void immunity() {
            StaffRule rule = ruleWhere(new Table()
                    .give(moderator, ModerationPermission.BAN.node())
                    .give(owner, StaffRule.IMMUNE));

            assertThat(rule.isImmune(owner)).isTrue();
            assertThat(rule.canAct(moderator, owner, ModerationPermission.BAN).refusal())
                    .contains(StaffRule.THEY_ARE_IMMUNE);
        }

        @Test
        @DisplayName("the console is not stopped by immunity, or an immune account cannot be undone")
        void immunityDoesNotBindTheConsole() {
            // Immunity is what stops one moderator banning another in a fit of pique. It must not be
            // what stops the server owner dealing with a compromised staff account.
            StaffRule rule = ruleWhere(new Table().give(owner, StaffRule.IMMUNE));

            assertThat(rule.canAct(null, owner, ModerationPermission.BAN).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("an immune moderator is still not allowed to punish another immune one")
        void immunityIsAboutTheSubject() {
            StaffRule rule = ruleWhere(new Table()
                    .give(moderator, ModerationPermission.BAN.node())
                    .give(moderator, StaffRule.IMMUNE)
                    .give(owner, StaffRule.IMMUNE));

            assertThat(rule.canAct(moderator, owner, ModerationPermission.BAN).isRefused()).isTrue();
        }

        @Test
        @DisplayName("the permission is checked before the subject, so a stranger learns nothing")
        void permissionIsCheckedFirst() {
            // Somebody without the permission must not be able to discover who is immune by watching
            // which refusal they get.
            StaffRule rule = ruleWhere(new Table().give(owner, StaffRule.IMMUNE));

            assertThat(rule.canAct(player, owner, ModerationPermission.BAN).refusal())
                    .contains(StaffRule.NO_PERMISSION);
        }

        @Test
        @DisplayName("a missing subject is refused rather than acted on")
        void aMissingSubject() {
            StaffRule rule = ruleWhere(new Table().give(moderator, ModerationPermission.BAN.node()));

            assertThat(rule.canAct(moderator, null, ModerationPermission.BAN).isRefused()).isTrue();
        }
    }

    @Test
    @DisplayName("the rule says what it is about")
    void itDescribesItself() {
        assertThat(ruleWhere(new Table()).describe()).isNotBlank();
    }
}
