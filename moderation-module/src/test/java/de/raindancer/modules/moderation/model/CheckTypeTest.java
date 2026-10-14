package de.raindancer.modules.moderation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CheckTypeTest {

    @Test
    @DisplayName("every family has a key that finds it again, and no two share one")
    void keys() {
        for (CheckType check : CheckType.values()) {
            assertThat(CheckType.find(check.key())).contains(check);
            assertThat(CheckType.find(check.title())).contains(check);
            assertThat(CheckType.find(check.name())).contains(check);
        }
        assertThat(Arrays.stream(CheckType.values()).map(CheckType::key).distinct().count())
                .isEqualTo(CheckType.values().length);
    }

    @Test
    @DisplayName("an alert comes before a kick, a kick before a ban; only the client policy is proof at once")
    void levels() {
        for (CheckType check : CheckType.values()) {
            assertThat(check.alertAt()).isPositive();
            if (check.kickAt() > 0) {
                assertThat(check.kickAt()).isGreaterThan(check.alertAt());
            }
            if (check.banAt() > 0 && check.kickAt() > 0) {
                assertThat(check.banAt()).isGreaterThan(check.kickAt());
            }
        }
        assertThat(Arrays.stream(CheckType.values()).filter(CheckType::impossible)).containsExactly(CheckType.CLIENT);
    }

    @Test
    @DisplayName("a key of a check from before 2.0 (fly, reach …) finds nothing, so old records are left alone")
    void oldKeys() {
        assertThat(CheckType.find("fly")).isEmpty();
        assertThat(CheckType.find("reach")).isEmpty();
    }
}
