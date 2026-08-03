package de.raindancer.modules.moderation;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.model.StaffRank;
import de.raindancer.modules.moderation.rules.BanLimitRule;
import de.raindancer.modules.moderation.rules.StaffRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How long a ban this person is allowed to hand out.
 *
 * <h2>Why a cap and not simply a permission</h2>
 * Because "may they ban?" is the wrong question. A mod dealing with a griefer at two in the morning
 * needs to be able to stop them <em>now</em>; what they should not be able to do is end somebody's time
 * on the server permanently on their own judgement. So a mod bans for up to a day and an admin bans for
 * as long as they like — which is one permission each and a length limit between them.
 *
 * <h2>Why it is a rule rather than a check inside the command</h2>
 * Because the screens have to ask it too, before anything is pressed: the duration menu greys what a mod
 * may not choose and says why, rather than letting them pick a week and refusing afterwards. A limit
 * enforced only at the moment of the click is a limit the UI lies about.
 */
class BanLimitRuleTest {

    /** A permission table — the whole reason the rule takes an interface rather than a Player. */
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

    private static final Duration A_DAY = Duration.ofDays(1);

    private final UUID mod = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();

    private BanLimitRule ruleWhere(Table table) {
        return new BanLimitRule(new StaffRule(table), A_DAY);
    }

    private Table staffed() {
        return new Table()
                .give(mod, ModerationPermission.TEMPBAN.node())
                .give(admin, ModerationPermission.TEMPBAN.node())
                .give(admin, ModerationPermission.BAN.node());
    }

    @Nested
    @DisplayName("an admin")
    class AnAdmin {

        @Test
        @DisplayName("may ban for as long as they like, permanently included")
        void anyLength() {
            BanLimitRule rule = ruleWhere(staffed());

            assertThat(rule.mayBanFor(admin, Sentence.forEver()).isAllowed()).isTrue();
            assertThat(rule.mayBanFor(admin, Sentence.of(Duration.ofDays(365))).isAllowed()).isTrue();
            assertThat(rule.mayBanFor(admin, Sentence.of(Duration.ofMinutes(5))).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("has no ceiling")
        void noCeiling() {
            assertThat(ruleWhere(staffed()).longestFor(admin)).isEmpty();
        }
    }

    @Nested
    @DisplayName("a mod")
    class AMod {

        @Test
        @DisplayName("may ban up to the cap")
        void upToTheCap() {
            BanLimitRule rule = ruleWhere(staffed());

            assertThat(rule.mayBanFor(mod, Sentence.of(Duration.ofHours(2))).isAllowed()).isTrue();
            assertThat(rule.mayBanFor(mod, Sentence.of(A_DAY)).isAllowed())
                    .as("exactly the cap is within it — a limit of 'up to a day' includes the day")
                    .isTrue();
        }

        @Test
        @DisplayName("may not ban for longer than the cap")
        void notLonger() {
            BanLimitRule rule = ruleWhere(staffed());

            assertThat(rule.mayBanFor(mod, Sentence.of(Duration.ofDays(2))).refusal())
                    .contains(BanLimitRule.TOO_LONG);
            assertThat(rule.mayBanFor(mod, Sentence.of(A_DAY.plusSeconds(1))).refusal())
                    .contains(BanLimitRule.TOO_LONG);
        }

        @Test
        @DisplayName("may not ban permanently at all")
        void notForEver() {
            // The one thing the split exists for: stopping somebody now is a mod's job, ending their
            // time on the server for good is not.
            assertThat(ruleWhere(staffed()).mayBanFor(mod, Sentence.forEver()).refusal())
                    .contains(BanLimitRule.NOT_FOR_EVER);
        }

        @Test
        @DisplayName("is told what their ceiling is, so a screen can show it")
        void theCeiling() {
            assertThat(ruleWhere(staffed()).longestFor(mod)).contains(A_DAY);
        }

        @Test
        @DisplayName("a length over the cap is clamped rather than refused, where a screen wants that")
        void clamped() {
            // What the duration menu uses: a mod picking the reason "Griefing", whose ladder starts at
            // three days, gets a day and a line saying an admin can go further — rather than a button
            // that refuses them.
            BanLimitRule rule = ruleWhere(staffed());

            assertThat(rule.clamp(mod, Sentence.of(Duration.ofDays(30))).length()).contains(A_DAY);
            assertThat(rule.clamp(mod, Sentence.forEver()).length()).contains(A_DAY);
            assertThat(rule.clamp(mod, Sentence.of(Duration.ofHours(3))).length())
                    .as("something already inside the cap is left exactly as it was")
                    .contains(Duration.ofHours(3));
            assertThat(rule.clamp(admin, Sentence.forEver()).isPermanent())
                    .as("an admin is not clamped")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("somebody with neither node")
    class NotStaff {

        @Test
        @DisplayName("may not ban at all")
        void notAtAll() {
            BanLimitRule rule = ruleWhere(staffed());

            assertThat(rule.mayBanFor(player, Sentence.of(Duration.ofMinutes(1))).refusal())
                    .contains(StaffRule.NO_PERMISSION);
            assertThat(rule.longestFor(player)).contains(Duration.ZERO);
            assertThat(rule.mayBanAtAll(player)).isFalse();
        }
    }

    @Nested
    @DisplayName("the console")
    class Console {

        @Test
        @DisplayName("is not capped")
        void notCapped() {
            // A null actor is the console, which the whole module treats as the server owner.
            BanLimitRule rule = ruleWhere(new Table());

            assertThat(rule.mayBanFor(null, Sentence.forEver()).isAllowed()).isTrue();
            assertThat(rule.longestFor(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("lifting one")
    class Lifting {

        @Test
        @DisplayName("a mod may lift a ban that was not permanent")
        void aTemporaryOne() {
            // Otherwise a mod can hand out a day and then not undo it when the griefer turns out to
            // have been the one being griefed.
            assertThat(ruleWhere(staffed()).mayLift(mod, false).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("a mod may not lift a permanent one")
        void aPermanentOne() {
            // A permanent ban is an admin's decision, and undoing one is the same decision reversed.
            assertThat(ruleWhere(staffed()).mayLift(mod, true).refusal())
                    .contains(BanLimitRule.NOT_YOURS_TO_LIFT);
        }

        @Test
        @DisplayName("an admin may lift either")
        void anAdminLiftsAnything() {
            BanLimitRule rule = ruleWhere(staffed());

            assertThat(rule.mayLift(admin, true).isAllowed()).isTrue();
            assertThat(rule.mayLift(admin, false).isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("what the ranks get")
    class TheRanks {

        @Test
        @DisplayName("a mod is given tempban and not ban")
        void aModsNodes() {
            assertThat(StaffRank.MOD.nodes())
                    .contains(ModerationPermission.TEMPBAN.node())
                    .doesNotContain(ModerationPermission.BAN.node());
        }

        @Test
        @DisplayName("an admin is given both")
        void anAdminsNodes() {
            assertThat(StaffRank.ADMIN.nodes())
                    .contains(ModerationPermission.TEMPBAN.node(),
                            ModerationPermission.BAN.node());
        }

        @Test
        @DisplayName("a trial mod is given neither")
        void aTrialsNodes() {
            assertThat(StaffRank.TRIAL_MOD.nodes())
                    .doesNotContain(ModerationPermission.TEMPBAN.node(),
                            ModerationPermission.BAN.node());
        }
    }

    @Test
    @DisplayName("the cap comes from the settings, and its default is a day")
    void theDefaultCap() {
        assertThat(ModerationSettings.DEFAULTS.modTempBanMax()).isEqualTo("1d");
        assertThat(Sentence.parse(ModerationSettings.DEFAULTS.modTempBanMax()))
                .as("a cap the module cannot read is a cap that silently becomes no cap, or no ban")
                .hasValueSatisfying(sentence -> assertThat(sentence.length()).contains(A_DAY));
    }

    @Test
    @DisplayName("only bans are capped — a mute or a freeze is not this rule's business")
    void onlyBans() {
        assertThat(BanLimitRule.appliesTo(PunishmentKind.BAN)).isTrue();
        assertThat(BanLimitRule.appliesTo(PunishmentKind.MUTE)).isFalse();
        assertThat(BanLimitRule.appliesTo(PunishmentKind.FREEZE)).isFalse();
        assertThat(BanLimitRule.appliesTo(null)).isFalse();
    }

    @Test
    @DisplayName("the rule says what it is about")
    void itDescribesItself() {
        assertThat(ruleWhere(staffed()).describe()).isNotBlank();
    }
}
