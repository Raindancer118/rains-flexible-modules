package de.raindancer.modules.wrapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoreVersionTest {

    @Test
    @DisplayName("an older RainsCore than the jar was built against is caught, a newer or equal one is not")
    void older() {
        assertThat(CoreVersion.isOlder("1.39.0", "1.41.0")).isTrue();
        assertThat(CoreVersion.isOlder("1.9.9", "1.10.0")).isTrue();
        assertThat(CoreVersion.isOlder("1.41.0", "1.41.0")).isFalse();
        assertThat(CoreVersion.isOlder("1.42.0", "1.41.0")).isFalse();
        assertThat(CoreVersion.isOlder("2.0.0-SNAPSHOT", "1.41.0")).isFalse();
    }

    @Test
    @DisplayName("something unreadable is never reported as too old — a guess would be a false alarm")
    void unreadable() {
        assertThat(CoreVersion.isOlder("dev", "1.41.0")).isFalse();
        assertThat(CoreVersion.isOlder("1.40.0", null)).isFalse();
    }

    @Test
    @DisplayName("the build writes the version it compiled against into the jar")
    void builtAgainst() {
        assertThat(CoreVersion.builtAgainst()).isPresent();
        assertThat(CoreVersion.builtAgainst().orElseThrow()).matches("\\d+\\.\\d+\\.\\d+.*");
    }
}
