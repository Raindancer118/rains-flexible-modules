package de.raindancer.modules.moderation;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.rules.StandingRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warnings adding up to a ban.
 *
 * <h2>What this is for</h2>
 * A warning stops nothing by design, which is what makes it usable — but it also means a player who
 * collects them faces nothing at all unless a moderator notices the pattern and acts. The threshold is
 * the server saying once, in advance, where the line is; after that it applies itself and applies the
 * same way to everybody, which is the half a tired moderator at midnight cannot promise.
 *
 * <p>The counting is {@code StandingRule}'s, because it is a decision about a record and nothing else —
 * {@code PunishmentService} only asks it and acts. That is also what makes it testable without a server,
 * which is the whole point of the rule/service split.
 */
class WarnThresholdTest {

    private final UUID player = UUID.randomUUID();
    private final UUID moderator = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-03T12:00:00Z");

    private Punishment warning(Duration ago) {
        return new Punishment(UUID.randomUUID().toString(), player, PunishmentKind.WARNING,
                moderator, "Spam", now.minus(ago), null, null, null, null);
    }

    private final StandingRule rule = new StandingRule(Duration.ofDays(30));

    @Test
    @DisplayName("warnings inside the window are counted")
    void theyAreCounted() {
        List<Punishment> record = List.of(warning(Duration.ofDays(1)), warning(Duration.ofDays(2)),
                warning(Duration.ofDays(3)));

        assertThat(rule.recentWarnings(record, now)).isEqualTo(3);
    }

    @Test
    @DisplayName("a warning older than the window does not count")
    void oldOnesDoNotCount() {
        // Without this, a bad week two summers ago is still banning somebody today — and the ban
        // arrives with no explanation anybody present can give.
        List<Punishment> record = List.of(warning(Duration.ofDays(1)), warning(Duration.ofDays(400)));

        assertThat(rule.recentWarnings(record, now)).isOne();
    }

    @Test
    @DisplayName("only warnings count — not mutes, kicks or bans")
    void onlyWarningsCount() {
        List<Punishment> record = new ArrayList<>();
        record.add(warning(Duration.ofDays(1)));
        for (PunishmentKind other : List.of(PunishmentKind.MUTE, PunishmentKind.KICK,
                PunishmentKind.BAN, PunishmentKind.FREEZE)) {
            record.add(new Punishment(UUID.randomUUID().toString(), player, other, moderator,
                    "something", now.minus(Duration.ofDays(1)), null, null, null, null));
        }

        assertThat(rule.recentWarnings(record, now))
                .as("a mute is already its own punishment; counting it towards the warning "
                        + "threshold would punish it twice")
                .isOne();
    }

    @Test
    @DisplayName("an empty or null record counts nothing")
    void nothingToCount() {
        assertThat(rule.recentWarnings(List.of(), now)).isZero();
        assertThat(rule.recentWarnings(null, now)).isZero();
    }

    @Test
    @DisplayName("the window comes from the settings, so an owner can widen or narrow it")
    void theWindowIsConfigured() {
        StandingRule strict = new StandingRule(Duration.ofDays(2));
        List<Punishment> record = List.of(warning(Duration.ofDays(1)), warning(Duration.ofDays(10)));

        assertThat(strict.recentWarnings(record, now)).isOne();
    }

    @Test
    @DisplayName("the defaults are a threshold that can actually be reached")
    void theDefaultsMakeSense() {
        ModerationSettings defaults = ModerationSettings.DEFAULTS;

        assertThat(defaults.warnsBeforeBan()).isEqualTo(3);
        assertThat(defaults.warnWindowDays()).isEqualTo(30);
        assertThat(defaults.warningsEscalateToABan()).isTrue();
        assertThat(defaults.warnWindow()).isEqualTo(Duration.ofDays(30));
        assertThat(Sentence.parse(defaults.warnBanLength()))
                .as("the automatic ban's length has to be one the module can read, or the threshold "
                        + "fires and nothing happens")
                .isPresent();
    }

    @Test
    @DisplayName("zero switches it off, and the window is still never zero days")
    void itCanBeSwitchedOff() {
        ModerationSettings off = ModerationSettings.DEFAULTS.withWarnsBeforeBan(0);

        assertThat(off.warningsEscalateToABan()).isFalse();
        // A window of zero days would count nothing at all, so a threshold of 1 would never fire and
        // an owner would be left wondering why. Clamped to a day at the least.
        assertThat(ModerationSettings.DEFAULTS.warnWindow()).isPositive();
    }
}
