package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.essentials.model.Nickname;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NicknameRuleTest {

    private final NicknameRule rule = new NicknameRule();

    @Nested
    @DisplayName("blank")
    class Blank {

        @Test
        void refusesIt() {
            Verdict verdict = rule.judge(
                    NicknameRule.Request.of(Nickname.of(""), 16, false));

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo(NicknameRule.BLANK);
        }
    }

    @Nested
    @DisplayName("too long")
    class TooLong {

        @Test
        void refusesOverTheLimit() {
            Verdict verdict = rule.judge(
                    NicknameRule.Request.of(Nickname.of("ThisNameIsWayTooLong"), 10, false));

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo(NicknameRule.TOO_LONG);
            assertThat(verdict.detail()).isEqualTo("10");
        }

        @Test
        void colourDoesNotCountTowardsTheLimit() {
            // Sixteen characters of colour, three of actual name — over the limit only if the
            // markup were counted, which is exactly the mistake this rule must not make.
            Verdict verdict = rule.judge(
                    NicknameRule.Request.of(Nickname.of("<red>Tom</red>"), 10, false));

            assertThat(verdict.isAllowed()).isTrue();
        }

        @Test
        void exactlyAtTheLimitIsAllowed() {
            Verdict verdict = rule.judge(
                    NicknameRule.Request.of(Nickname.of("1234567890"), 10, false));

            assertThat(verdict.isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("somebody's real name")
    class RealName {

        @Test
        void isRefused() {
            Verdict verdict = rule.judge(
                    NicknameRule.Request.of(Nickname.of("Raindancer118"), 16, true));

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo(NicknameRule.NAME_TAKEN);
        }
    }

    @Nested
    @DisplayName("blocklisted")
    class Blocklisted {

        @Test
        @DisplayName("a reported-only match is refused as BLOCKED")
        void reportedIsRefused() {
            Verdict verdict = rule.judge(new NicknameRule.Request(Nickname.of("admin"), 16, false,
                    NicknameRule.BlockMatch.REPORTED));

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo(NicknameRule.BLOCKED);
        }

        @Test
        @DisplayName("a banned-tier match is refused as BLOCKED too — severity is the caller's to act on")
        void bannedIsRefused() {
            Verdict verdict = rule.judge(new NicknameRule.Request(Nickname.of("slur"), 16, false,
                    NicknameRule.BlockMatch.BANNED));

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo(NicknameRule.BLOCKED);
        }

        @Test
        @DisplayName("takes priority over the length check, so a long blocked name is still caught as blocked")
        void takesPriorityOverLength() {
            Verdict verdict = rule.judge(new NicknameRule.Request(
                    Nickname.of("ThisNameIsWayTooLongAndAlsoBlocked"), 10, false,
                    NicknameRule.BlockMatch.BANNED));

            assertThat(verdict.reason()).isEqualTo(NicknameRule.BLOCKED);
        }
    }

    @Test
    @DisplayName("a nickname that fits and belongs to nobody is allowed")
    void allowsAGoodOne() {
        Verdict verdict = rule.judge(
                NicknameRule.Request.of(Nickname.of("<red>Foxy</red>"), 16, false));

        assertThat(verdict.isAllowed()).isTrue();
    }
}
