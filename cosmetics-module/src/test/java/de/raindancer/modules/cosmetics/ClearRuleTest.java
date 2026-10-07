package de.raindancer.modules.cosmetics;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.cosmetics.model.ClearScope;
import de.raindancer.modules.cosmetics.rules.ClearRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may clear whose cosmetics, and what there is to clear — the one answer the command and the menu share. */
class ClearRuleTest {

    private final ClearRule rule = new ClearRule();

    private static ClearRule.Request request(ClearScope scope, boolean self, boolean maySelf, boolean mayOthers,
                                             boolean online, boolean name, boolean particle) {
        return new ClearRule.Request(scope, self, maySelf, mayOthers, online, name, particle);
    }

    @Nested
    @DisplayName("permissions")
    class Permissions {

        @Test
        @DisplayName("clearing your own needs the own node")
        void selfNeedsOwnNode() {
            Verdict verdict = rule.judge(request(ClearScope.ALL, true, false, true, true, true, true));
            assertThat(verdict.reason()).isEqualTo("cosmetics.clear.not-allowed");
        }

        @Test
        @DisplayName("clearing somebody else's needs the others node, whatever the own node says")
        void othersNeedOthersNode() {
            Verdict verdict = rule.judge(request(ClearScope.ALL, false, true, false, true, true, true));
            assertThat(verdict.reason()).isEqualTo("cosmetics.clear.not-allowed-others");
        }

        @Test
        @DisplayName("an admin may clear somebody without holding the own node")
        void adminNeedsNoOwnNode() {
            assertThat(rule.judge(request(ClearScope.NAME, false, false, true, true, true, false)).isAllowed())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("what there is to clear")
    class Content {

        @Test
        @DisplayName("nothing worn is refused with a sentence, not done silently")
        void nothingToClear() {
            Verdict verdict = rule.judge(request(ClearScope.ALL, true, true, false, true, false, false));
            assertThat(verdict.reason()).isEqualTo("cosmetics.clear.nothing");
        }

        @Test
        @DisplayName("asking for the name when only a particle is worn is nothing to clear")
        void scopeMatters() {
            Verdict verdict = rule.judge(request(ClearScope.NAME, true, true, false, true, false, true));
            assertThat(verdict.reason()).isEqualTo("cosmetics.clear.nothing");
        }

        @Test
        @DisplayName("particles of somebody who is not here cannot be reached, and the refusal says so")
        void offlineParticles() {
            Verdict verdict = rule.judge(request(ClearScope.PARTICLES, false, false, true, false, true, true));
            assertThat(verdict.reason()).isEqualTo("cosmetics.clear.offline-particles");
        }

        @Test
        @DisplayName("'all' on somebody offline still clears the name — the particle is just left")
        void allOnOfflineClearsTheName() {
            ClearRule.Request offline = request(ClearScope.ALL, false, false, true, false, true, true);
            assertThat(rule.judge(offline).isAllowed()).isTrue();
            ClearRule.Plan plan = rule.plan(offline);
            assertThat(plan.name()).isTrue();
            assertThat(plan.particles()).isFalse();
            assertThat(plan.particlesOutOfReach()).isTrue();
        }

        @Test
        @DisplayName("'all' on somebody online clears both")
        void allOnline() {
            ClearRule.Plan plan = rule.plan(request(ClearScope.ALL, true, true, false, true, true, true));
            assertThat(plan.name()).isTrue();
            assertThat(plan.particles()).isTrue();
            assertThat(plan.particlesOutOfReach()).isFalse();
        }

        @Test
        @DisplayName("only what is actually worn is in the plan")
        void onlyWhatIsWorn() {
            ClearRule.Plan plan = rule.plan(request(ClearScope.ALL, true, true, false, true, false, true));
            assertThat(plan.name()).isFalse();
            assertThat(plan.particles()).isTrue();
        }
    }

    @Nested
    @DisplayName("the words")
    class Words {

        @Test
        @DisplayName("name, particle(s) and all are understood, in any case")
        void parses() {
            assertThat(ClearScope.of("Name")).contains(ClearScope.NAME);
            assertThat(ClearScope.of("particle")).contains(ClearScope.PARTICLES);
            assertThat(ClearScope.of("PARTICLES")).contains(ClearScope.PARTICLES);
            assertThat(ClearScope.of("all")).contains(ClearScope.ALL);
            assertThat(ClearScope.of("everything")).isEmpty();
            assertThat(ClearScope.of(null)).isEmpty();
        }

        @Test
        @DisplayName("what to type is listed for tab completion")
        void keys() {
            assertThat(ClearScope.keys()).containsExactly("name", "particles", "all");
        }
    }
}
