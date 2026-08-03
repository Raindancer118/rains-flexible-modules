package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.StaffRank;
import de.raindancer.modules.moderation.rules.PromotionRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may hand out which rank.
 *
 * <h2>The rule everything follows from</h2>
 * You may only appoint <em>below</em> yourself. A rank that can create its own equal can create a second
 * of itself, and then two of those, and the ladder has no top — which is the same hole as a moderator
 * being able to promote themselves, arrived at one step more slowly.
 *
 * <p>The owner is outside all of it and may hand out anything, including another owner.
 */
class PromotionRuleTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID mod = UUID.randomUUID();
    private final UUID trial = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();

    private final Map<UUID, StaffRank> ranks = new HashMap<>();

    PromotionRuleTest() {
        ranks.put(admin, StaffRank.ADMIN);
        ranks.put(mod, StaffRank.MOD);
        ranks.put(trial, StaffRank.TRIAL_MOD);
    }

    private PromotionRule rule() {
        return rule(true, true);
    }

    private PromotionRule rule(boolean promoting, boolean demoting) {
        return new PromotionRule(who -> who == null || owner.equals(who),
                who -> Optional.ofNullable(ranks.get(who)), promoting, demoting);
    }

    @Nested
    @DisplayName("the owner")
    class Owner {

        @Test
        @DisplayName("may hand out anything, to anybody")
        void anything() {
            for (StaffRank rank : StaffRank.values()) {
                assertThat(rule().mayPromote(owner, player, rank).isAllowed()).isTrue();
                assertThat(rule().mayPromote(owner, admin, rank).isAllowed()).isTrue();
            }
            assertThat(rule().mayDemote(owner, admin).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("is not stopped by the settings, which are about everybody else")
        void notStoppedBySettings() {
            assertThat(rule(false, false).mayPromote(owner, player, StaffRank.ADMIN).isAllowed())
                    .isTrue();
            assertThat(rule(false, false).mayDemote(owner, admin).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("the console counts as the owner")
        void theConsole() {
            assertThat(rule().mayPromote(null, player, StaffRank.OWNER).isAllowed()).isTrue();
            assertThat(rule().mayDemote(null, admin).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("may hand out the top rank, so a server can have a second owner")
        void theTopRank() {
            assertThat(rule().highestTheyMayGive(owner)).contains(StaffRank.OWNER);
        }
    }

    @Nested
    @DisplayName("everybody else appoints below themselves")
    class BelowThemselves {

        @Test
        @DisplayName("an admin may appoint a mod")
        void anAdminAppointsAMod() {
            assertThat(rule().mayPromote(admin, player, StaffRank.MOD).isAllowed()).isTrue();
            assertThat(rule().highestTheyMayGive(admin)).contains(StaffRank.MOD);
        }

        @Test
        @DisplayName("an admin may not appoint another admin")
        void notAnEqual() {
            // The hole this closes: a rank that can create its own equal can create a second of itself,
            // and the ladder then has no top.
            assertThat(rule().mayPromote(admin, player, StaffRank.ADMIN).refusal())
                    .contains(PromotionRule.ONLY_BELOW_YOU);
        }

        @Test
        @DisplayName("an admin may not appoint an owner")
        void notAboveThemselves() {
            assertThat(rule().mayPromote(admin, player, StaffRank.OWNER).refusal())
                    .contains(PromotionRule.ONLY_BELOW_YOU);
        }

        @Test
        @DisplayName("a mod may appoint a trial mod and nothing more")
        void aModAppointsATrial() {
            assertThat(rule().mayPromote(mod, player, StaffRank.TRIAL_MOD).isAllowed()).isTrue();
            assertThat(rule().mayPromote(mod, player, StaffRank.MOD).isRefused()).isTrue();
            assertThat(rule().highestTheyMayGive(mod)).contains(StaffRank.TRIAL_MOD);
        }

        @Test
        @DisplayName("a trial mod may appoint nobody, because there is nothing below them")
        void aTrialAppointsNobody() {
            assertThat(rule().highestTheyMayGive(trial)).isEmpty();
            assertThat(rule().mayHandOutAnything(trial)).isFalse();
            assertThat(rule().mayPromote(trial, player, StaffRank.TRIAL_MOD).isRefused()).isTrue();
        }

        @Test
        @DisplayName("an ordinary player may appoint nobody")
        void aPlayerAppointsNobody() {
            assertThat(rule().mayPromote(player, trial, StaffRank.TRIAL_MOD).refusal())
                    .contains(PromotionRule.NOT_YOURS);
        }
    }

    @Nested
    @DisplayName("and only on somebody below them")
    class OnlyDownwards {

        @Test
        @DisplayName("a mod may not re-rank another mod")
        void notAnEqual() {
            UUID anotherMod = UUID.randomUUID();
            ranks.put(anotherMod, StaffRank.MOD);

            assertThat(rule().mayPromote(mod, anotherMod, StaffRank.TRIAL_MOD).refusal())
                    .contains(PromotionRule.NOT_ABOVE_YOU);
            assertThat(rule().mayDemote(mod, anotherMod).refusal())
                    .contains(PromotionRule.NOT_ABOVE_YOU);
        }

        @Test
        @DisplayName("a mod may not touch an admin")
        void notSomebodyAbove() {
            // Otherwise "promoting" an admin down to trial mod is a demotion wearing the other word.
            assertThat(rule().mayPromote(mod, admin, StaffRank.TRIAL_MOD).refusal())
                    .contains(PromotionRule.NOT_ABOVE_YOU);
            assertThat(rule().mayDemote(mod, admin).refusal())
                    .contains(PromotionRule.NOT_ABOVE_YOU);
        }

        @Test
        @DisplayName("an admin may demote a mod")
        void somebodyBelow() {
            assertThat(rule().mayDemote(admin, mod).isAllowed()).isTrue();
            assertThat(rule().mayDemote(admin, trial).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("nobody changes their own rank")
        void notThemselves() {
            // Not even to go down: a mod who can demote themselves can demote themselves to trial and
            // back, and the audit trail then shows a rank changing for no reason anybody can explain.
            assertThat(rule().mayPromote(admin, admin, StaffRank.MOD).refusal())
                    .contains(PromotionRule.YOURSELF);
            assertThat(rule().mayDemote(admin, admin).refusal())
                    .contains(PromotionRule.YOURSELF);
        }
    }

    @Nested
    @DisplayName("with the settings off")
    class SwitchedOff {

        @Test
        @DisplayName("promoting below is refused, and says why")
        void promotingOff() {
            assertThat(rule(false, true).mayPromote(admin, player, StaffRank.MOD).refusal())
                    .contains(PromotionRule.HANDING_OUT_IS_OFF);
            assertThat(rule(false, true).mayHandOutAnything(admin)).isFalse();
        }

        @Test
        @DisplayName("demoting below is refused separately")
        void demotingOff() {
            // Separate on purpose: appointing somebody who turns out badly is recoverable; removing
            // somebody in a temper during an argument is what the audit trail gets read about.
            assertThat(rule(true, false).mayDemote(admin, mod).refusal())
                    .contains(PromotionRule.HANDING_OUT_IS_OFF);
            assertThat(rule(true, false).mayPromote(admin, player, StaffRank.MOD).isAllowed())
                    .as("the two directions are independent")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the defaults let staff appoint below themselves")
    void theDefaults() {
        assertThat(ModerationSettings.DEFAULTS.mayPromoteBelow()).isTrue();
        assertThat(ModerationSettings.DEFAULTS.mayDemoteBelow()).isTrue();
    }

    @Test
    @DisplayName("the rule says what it is about")
    void itDescribesItself() {
        assertThat(rule().describe()).isNotBlank();
    }
}
