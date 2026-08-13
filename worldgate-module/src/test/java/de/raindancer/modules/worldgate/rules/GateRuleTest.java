package de.raindancer.modules.worldgate.rules;

import de.raindancer.modules.worldgate.model.GateState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a player may cross a managed dimension's border, before anything is cancelled.
 */
class GateRuleTest {

    private final GateRule rule = new GateRule();

    @Nested
    @DisplayName("an open dimension")
    class Open {

        @Test
        @DisplayName("always allows entry")
        void allowsEntry() {
            assertThat(rule.allowed(GateState.OPEN, true, false)).isTrue();
        }

        @Test
        @DisplayName("always allows leaving")
        void allowsLeaving() {
            assertThat(rule.allowed(GateState.OPEN, false, false)).isTrue();
        }
    }

    @Nested
    @DisplayName("a drained dimension")
    class Drained {

        @Test
        @DisplayName("refuses entry without the bypass")
        void refusesEntry() {
            assertThat(rule.allowed(GateState.DRAINED, true, false)).isFalse();
        }

        @Test
        @DisplayName("still allows leaving — winding down never traps anybody inside")
        void stillAllowsLeaving() {
            assertThat(rule.allowed(GateState.DRAINED, false, false)).isTrue();
        }

        @Test
        @DisplayName("allows entry with the bypass")
        void bypassAllowsEntry() {
            assertThat(rule.allowed(GateState.DRAINED, true, true)).isTrue();
        }
    }

    @Nested
    @DisplayName("a closed dimension")
    class Closed {

        @Test
        @DisplayName("refuses entry without the bypass")
        void refusesEntry() {
            assertThat(rule.allowed(GateState.CLOSED, true, false)).isFalse();
        }

        @Test
        @DisplayName("still allows leaving")
        void stillAllowsLeaving() {
            assertThat(rule.allowed(GateState.CLOSED, false, false)).isTrue();
        }

        @Test
        @DisplayName("allows entry with the bypass")
        void bypassAllowsEntry() {
            assertThat(rule.allowed(GateState.CLOSED, true, true)).isTrue();
        }
    }

    @Nested
    @DisplayName("the bypass permission")
    class Bypass {

        @Test
        @DisplayName("is never blocked, in either direction, in either locked state")
        void neverBlocked() {
            for (GateState state : GateState.values()) {
                assertThat(rule.allowed(state, true, true)).as("%s entering", state).isTrue();
                assertThat(rule.allowed(state, false, true)).as("%s leaving", state).isTrue();
            }
        }
    }

    @Test
    @DisplayName("a missing state is treated as open")
    void nullStateIsTreatedAsOpen() {
        assertThat(rule.allowed(null, true, false)).isTrue();
    }

    @Test
    @DisplayName("a rule decides and does nothing else")
    void describesItself() {
        assertThat(rule.describe()).isNotBlank();
    }
}
