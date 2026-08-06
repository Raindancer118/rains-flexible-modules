package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.rules.ConfigurationRules;
import de.raindancer.core.data.settings.SettingsAudit;
import de.raindancer.core.data.settings.SettingsAudit.Finding;
import de.raindancer.modules.hungergames.store.BorderPhaseStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup check: what it says about a configuration, and — as much of the point — what it stays quiet
 * about.
 *
 * <h2>The failure mode this whole class exists against</h2>
 * A check that cries wolf is worse than no check. Every warning here appears on the console of a server
 * that is about to run a tournament, and the moment one of them is routinely wrong, the whole block gets
 * scrolled past — including the one that was right. So there are two kinds of test below in equal
 * numbers: "this broken thing is reported" and "this perfectly ordinary thing is not".
 */
class ConfigurationRulesTest {

    private final ConfigurationRules rules = new ConfigurationRules();

    private static final HungerGamesSettings DEFAULTS = HungerGamesSettings.DEFAULTS;

    /** The v1 plugin's shipped phase plan — what an upgrading server actually arrives with. */
    private static List<BorderPhaseConfig> v1Phases(Duration round) {
        return List.of(
                BorderPhaseStore.parse("50% -> 1000 @ max:2.5", round),
                BorderPhaseStore.parse("80% -> 200 @ max:2.5", round));
    }

    private static String messages(SettingsAudit audit) {
        return String.join(" | ", audit.findings().stream().map(Finding::message).toList());
    }

    @Nested
    @DisplayName("a configuration nobody has broken")
    class Quiet {

        @Test
        @DisplayName("the shipped defaults, with no phases configured yet, say nothing at all")
        void aFreshInstallIsSilent() {
            // A server that has not set up a border shrink yet is a normal starting state, not a fault.
            // Warning about it every boot is exactly how this block becomes noise.
            assertThat(rules.check(DEFAULTS, List.of()).findings()).isEmpty();
        }

        @Test
        @DisplayName("the defaults with the v1 phase plan say nothing either")
        void theUpgradePathIsSilent() {
            SettingsAudit findings = rules.check(DEFAULTS, v1Phases(DEFAULTS.roundDuration()));

            assertThat(findings.findings())
                    .as("an upgrading server changes nothing and must not be greeted by a wall of "
                            + "warnings: %s", messages(findings))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the clock")
    class TheClock {

        @Test
        @DisplayName("a run-up longer than the round is reported as broken")
        void theRoundWouldBeOverBeforeItStarted() {
            HungerGamesSettings silly = Tweak.of(DEFAULTS, "gameDurationMinutes", 5, "gracePeriodSeconds", 600);

            SettingsAudit findings = rules.check(silly, List.of());

            assertThat(findings.findings()).isNotEmpty();
            assertThat(findings.findings().get(0).isBroken()).isTrue();
            assertThat(messages(findings)).contains("grace period");
        }

        @Test
        @DisplayName("a run-up eating most of the round is questioned but not called broken")
        void aLongRunUpIsMerelyOdd() {
            // 5-minute round, 60s grace + 20s countdown = 80s, which is more than a quarter of it.
            HungerGamesSettings tight = Tweak.of(DEFAULTS, "gameDurationMinutes", 5);

            SettingsAudit findings = rules.check(tight, List.of());

            assertThat(findings.findings()).isNotEmpty();
            assertThat(findings.hasBroken()).isFalse();
        }
    }

    @Nested
    @DisplayName("the border")
    class Border {

        @Test
        @DisplayName("a border still closing when the round ends is reported")
        void itNeverGetsThere() {
            // Phases at 50% and 80% of a round that is only 20 minutes long: the second triggers at 16
            // minutes and needs over five to travel, at a ceiling of 1.25 b/s.
            HungerGamesSettings shortRound = Tweak.of(DEFAULTS, "gameDurationMinutes", 20);

            SettingsAudit findings = rules.check(shortRound, v1Phases(shortRound.roundDuration()));

            assertThat(findings.hasBroken()).isTrue();
            assertThat(messages(findings)).containsAnyOf("still closing", "only stands at that size");
        }

        @Test
        @DisplayName("a border that stands at its final size for too little of the round is reported")
        void thereIsNoTimeAtTheFinalSize() {
            // Finishes on time, but only just: the last phase triggers at 95% of the round.
            Duration round = DEFAULTS.roundDuration();
            List<BorderPhaseConfig> lateFinish = List.of(
                    BorderPhaseStore.parse("50% -> 1000 @ max:2.5", round),
                    BorderPhaseStore.parse("95% -> 900 @ max:2.5", round));

            SettingsAudit findings = rules.check(DEFAULTS, lateFinish);

            assertThat(findings.findings())
                    .as("the border finishing is not the finish — see "
                            + "ConfigurationRules.MINIMUM_TIME_AT_FINAL_SIZE")
                    .anyMatch(Finding::isBroken);
            assertThat(messages(findings)).contains("only stands at that size");
        }

        @Test
        @DisplayName("the time at the final size the defaults leave is comfortably over the minimum")
        void theDefaultsLeaveRealTimeAtTheFinalSize() {
            // The number this is really asserting: with the v1 plan at the current ceiling the border
            // stops at 149 min of a 180 min round, so the endgame is about 30 minutes — twice the floor.
            SettingsAudit findings = rules.check(DEFAULTS, v1Phases(DEFAULTS.roundDuration()));

            assertThat(findings.findings()).isEmpty();
            assertThat(ConfigurationRules.MINIMUM_TIME_AT_FINAL_SIZE).isEqualTo(Duration.ofMinutes(15));
        }

        @Test
        @DisplayName("a border faster than somebody can dig is questioned")
        void aBorderNobodyCanDigAwayFrom() {
            SettingsAudit findings = rules.check(Tweak.of(DEFAULTS, "borderMaxEdgeSpeed", 2.5D), List.of());

            assertThat(messages(findings)).contains("iron pickaxe");
            assertThat(findings.findings())
                    .as("deliberate for some tournaments, so it is a question rather than a fault")
                    .noneMatch(Finding::isBroken);
        }

        @Test
        @DisplayName("a border starting below its own floor is broken")
        void nowhereToShrinkTo() {
            // Below the floor, which is 50. It used to be enough to say 50 here, when the floor was 100 —
            // a reminder that a test pinned to another setting's default is a test that quietly changes
            // meaning when that default does.
            SettingsAudit findings = rules.check(Tweak.of(DEFAULTS, "borderInitialSize", 40), List.of());

            assertThat(findings.hasBroken()).isTrue();
            assertThat(messages(findings)).contains("nowhere to shrink");
        }
    }

    @Nested
    @DisplayName("the deathmatch")
    class Deathmatch {

        @Test
        @DisplayName("a target below the border's floor is broken")
        void belowTheFloor() {
            // Half the floor. 50 is the floor itself now, and a target equal to it is correct rather than
            // broken — see HungerGamesSettingsTest on why the two match by default.
            SettingsAudit findings = rules.check(Tweak.of(DEFAULTS, "deathmatchTargetBorderSize", 25), List.of());

            assertThat(findings.hasBroken()).isTrue();
            assertThat(messages(findings)).contains("floor");
        }

        @Test
        @DisplayName("a target larger than the border ever is, is broken")
        void itWouldOpenTheArenaUp() {
            SettingsAudit findings = rules.check(Tweak.of(DEFAULTS, "deathmatchTargetBorderSize", 9000), List.of());

            assertThat(findings.hasBroken()).isTrue();
            assertThat(messages(findings)).contains("open the arena up");
        }

        @Test
        @DisplayName("a phase name that is not a phase is broken")
        void aPhaseThatDoesNotExist() {
            SettingsAudit findings = rules.check(
                    Tweak.of(DEFAULTS, "deathmatchAllowedPhases", List.of("RUNNIG")), List.of());

            assertThat(findings.hasBroken()).isTrue();
            assertThat(messages(findings))
                    .as("the point of naming the known phases in the message is that the typo is "
                            + "visible next to the right spelling")
                    .contains("RUNNING");
        }

        @Test
        @DisplayName("a deathmatch that would not close anything is reported")
        void itWouldCloseNothing() {
            // The phases already bring the arena to 200. A deathmatch target of 200 or more announces
            // itself, teleports everybody to the middle, and changes the border by nothing — which reads
            // to the people watching as the feature being broken rather than as a configuration choice.
            SettingsAudit findings = rules.check(
                    Tweak.of(DEFAULTS, "deathmatchTargetBorderSize", 200),
                    v1Phases(DEFAULTS.roundDuration()));

            assertThat(findings.hasBroken()).isTrue();
            assertThat(messages(findings)).contains("close nothing");
        }

        @Test
        @DisplayName("the default deathmatch target is genuinely smaller than the phases leave")
        void theDefaultsTightenTheArena() {
            // 100 against the phases' 200. This is the assertion that would have caught the reconciliation
            // being missing: before the check existed, the two features' numbers were never compared.
            assertThat(DEFAULTS.deathmatchTargetBorderSize())
                    .isLessThan((int) v1Phases(DEFAULTS.roundDuration()).get(1).targetSize());
            assertThat(rules.check(DEFAULTS, v1Phases(DEFAULTS.roundDuration())).findings()).isEmpty();
        }

        @Test
        @DisplayName("nothing is compared when no phases are configured")
        void withoutPhasesThereIsNothingToCompareAgainst() {
            // A fresh install has no phases, so "smaller than what the phases leave" has no meaning.
            // Reporting it anyway would fire on every new server.
            assertThat(rules.check(Tweak.of(DEFAULTS, "deathmatchTargetBorderSize", 200), List.of())
                    .findings()).isEmpty();
        }

        @Test
        @DisplayName("nothing is said when the deathmatch is switched off")
        void switchedOffIsSilent() {
            HungerGamesSettings off = Tweak.of(DEFAULTS, "deathmatchEnabled", false, "deathmatchTargetBorderSize", 9000);

            assertThat(rules.check(off, List.of()).findings())
                    .as("a nonsense value behind a switch that is off is not a problem anybody has")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("supply drops and monsters")
    class TheRest {

        @Test
        @DisplayName("a minimum radius beyond the maximum is broken")
        void noWhereToLand() {
            SettingsAudit findings = rules.check(Tweak.of(DEFAULTS, "supplyDropRadiusMin", 9999), List.of());

            assertThat(findings.hasBroken()).isTrue();
        }

        @Test
        @DisplayName("waves that run past the end of the round are questioned")
        void wavesThatNeverArrive() {
            HungerGamesSettings many = Tweak.of(DEFAULTS, "monsterWaveWaveCount", 100, "monsterWaveIntervalSeconds", 600);

            SettingsAudit findings = rules.check(many, List.of());

            assertThat(messages(findings)).contains("never arrive");
        }
    }

    @Nested
    @DisplayName("how it is reported")
    class Reporting {

        @Test
        @DisplayName("broken things come before questionable ones")
        void worstFirst() {
            // Both at once: a border nobody can dig away from (a question) and a deathmatch target
            // below the floor (broken). On a console the first line is the one that gets read.
            HungerGamesSettings both = Tweak.of(DEFAULTS, "borderMaxEdgeSpeed", 2.5D, "deathmatchTargetBorderSize", 25);

            SettingsAudit findings = rules.check(both, List.of());

            assertThat(findings.size()).isGreaterThanOrEqualTo(2);
            assertThat(findings.findings().get(0).isBroken()).isTrue();
            assertThat(findings.findings().get(findings.size() - 1).isBroken()).isFalse();
        }

        @Test
        @DisplayName("every message is a sentence somebody could act on")
        void nothingIsCryptic() {
            HungerGamesSettings broken = Tweak.of(DEFAULTS, "borderInitialSize", 50, "deathmatchTargetBorderSize", 9000,
                    "supplyDropRadiusMin", 9999);

            for (Finding finding : rules.check(broken, List.of()).findings()) {
                assertThat(finding.message())
                        .as("a warning nobody can act on is a warning nobody reads twice")
                        .isNotBlank()
                        .hasSizeGreaterThan(40);
                assertThat(finding.message())
                        .as("no placeholder survived into the text: %s", finding.message())
                        .doesNotContain("%s")
                        .doesNotContain("%d")
                        .doesNotContain("null");
            }
        }
    }
}
