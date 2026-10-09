package de.raindancer.modules.farmworld.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FarmPassesTest {

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1_000_000_000_000L);
    private final UUID who = UUID.randomUUID();

    private FarmPasses passes() {
        return new FarmPasses(dir.resolve("day-passes.yml"), now::get);
    }

    @Test
    @DisplayName("nobody has a pass until one is granted")
    void none() {
        assertThat(passes().isActive(who)).isFalse();
    }

    @Test
    @DisplayName("a pass lasts exactly as long as it was granted for, and no longer")
    void expires() {
        FarmPasses passes = passes();
        passes.grant(who, Duration.ofHours(24));

        now.addAndGet(Duration.ofHours(24).toMillis() - 1);
        assertThat(passes.isActive(who)).isTrue();

        now.addAndGet(1);
        assertThat(passes.isActive(who)).isFalse();
    }

    @Test
    @DisplayName("a pass survives a restart")
    void persists() {
        passes().grant(who, Duration.ofHours(24));

        FarmPasses reloaded = passes();
        reloaded.load();

        assertThat(reloaded.isActive(who)).isTrue();
        assertThat(reloaded.expiry(who)).isPresent();
    }

    @Test
    @DisplayName("a pass that ran out while the server was down is gone after loading")
    void expiredIsDroppedOnLoad() {
        passes().grant(who, Duration.ofHours(1));
        now.addAndGet(Duration.ofHours(2).toMillis());

        FarmPasses reloaded = passes();
        reloaded.load();

        assertThat(reloaded.isActive(who)).isFalse();
        assertThat(reloaded.tracked()).isZero();
    }

    @Test
    @DisplayName("one player's pass is not another's")
    void perPlayer() {
        FarmPasses passes = passes();
        passes.grant(who, Duration.ofHours(24));
        assertThat(passes.isActive(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("buying another while one runs starts from now, never shortens it")
    void renewing() {
        FarmPasses passes = passes();
        passes.grant(who, Duration.ofHours(24));
        now.addAndGet(Duration.ofHours(1).toMillis());
        passes.grant(who, Duration.ofHours(2));

        now.addAndGet(Duration.ofHours(10).toMillis());
        assertThat(passes.isActive(who)).as("the longer, earlier pass still stands").isTrue();
    }

    @Test
    @DisplayName("an unreadable file is not written over, so nobody's pass is lost to a typo")
    void unreadableIsKept() throws Exception {
        Path file = dir.resolve("day-passes.yml");
        Files.writeString(file, "passes: [unclosed");

        FarmPasses passes = passes();
        passes.load();
        passes.grant(who, Duration.ofHours(24));

        assertThat(passes.isActive(who)).as("still honoured this session").isTrue();
        assertThat(Files.list(dir).filter(p -> p.getFileName().toString().contains(".broken-")))
                .as("the damaged file is set aside, not destroyed").hasSize(1);
    }
}
