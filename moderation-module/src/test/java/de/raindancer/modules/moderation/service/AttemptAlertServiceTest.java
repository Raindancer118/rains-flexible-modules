package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.punishment.BlockedAttempts;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.rules.AttemptAlertRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptAlertServiceTest {

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final UUID mushy = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final List<AttemptAlertService.Alert> sent = new ArrayList<>();

    private BlockedAttempts.Attempt join(UUID who, String name) {
        return new BlockedAttempts.Attempt(Instant.ofEpochMilli(clock.get()), who, name, BlockedAttempts.Kind.JOIN,
                PunishmentKind.BAN, "griefing", "", null);
    }

    @Test
    @DisplayName("the first attempt is told at once, the next ones at most every N minutes, with how many were held back")
    void throttled() {
        AttemptAlertService alerts = new AttemptAlertService(new AttemptAlertRule(), sent::add, clock::get,
                ModerationSettings.DEFAULTS.withAttemptAlertMinutes(10));
        alerts.onAttempt(join(mushy, "MushyTheFish"));
        clock.addAndGet(Duration.ofMinutes(1).toMillis());
        alerts.onAttempt(join(mushy, "MushyTheFish"));
        alerts.onAttempt(join(bo, "Bo"));
        clock.addAndGet(Duration.ofMinutes(4).toMillis());
        alerts.onAttempt(join(mushy, "MushyTheFish"));
        clock.addAndGet(Duration.ofMinutes(6).toMillis());
        alerts.onAttempt(join(mushy, "MushyTheFish"));

        assertThat(sent).extracting(alert -> alert.attempt().name() + " +" + alert.heldBack())
                .containsExactly("MushyTheFish +0", "Bo +0", "MushyTheFish +2");
    }

    @Test
    @DisplayName("switched off in the settings, nobody is told")
    void switchedOff() {
        AttemptAlertService alerts = new AttemptAlertService(new AttemptAlertRule(), sent::add, clock::get,
                ModerationSettings.DEFAULTS.withAttemptAlerts(false));
        alerts.onAttempt(join(mushy, "MushyTheFish"));
        assertThat(sent).isEmpty();
        alerts.settings(ModerationSettings.DEFAULTS);
        alerts.onAttempt(join(mushy, "MushyTheFish"));
        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("the rule: never told is due; told within the quiet time is not")
    void rule() {
        AttemptAlertRule rule = new AttemptAlertRule();
        Instant now = Instant.ofEpochSecond(10_000);
        assertThat(rule.due(null, now, Duration.ofMinutes(10))).isTrue();
        assertThat(rule.due(now.minusSeconds(599), now, Duration.ofMinutes(10))).isFalse();
        assertThat(rule.due(now.minusSeconds(600), now, Duration.ofMinutes(10))).isTrue();
    }
}
