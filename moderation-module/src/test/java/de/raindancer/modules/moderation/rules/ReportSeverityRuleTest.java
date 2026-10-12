package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.ReportSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReportSeverityRuleTest {

    private final ReportSeverityRule rule = new ReportSeverityRule();
    private final UUID bram = UUID.randomUUID();

    private Report about(UUID reporter, String preset) {
        return Report.filed(UUID.randomUUID().toString(), reporter, "someone", bram, "Bram", "text", Instant.EPOCH)
                .withPreset(preset);
    }

    @Test
    @DisplayName("each preset brings its own weight: spam is low, hate speech critical")
    void byPreset() {
        Report spam = about(UUID.randomUUID(), "SPAM");
        Report hate = about(UUID.randomUUID(), "HATE_SPEECH");
        assertThat(rule.judge(spam, List.of(spam))).isEqualTo(ReportSeverity.LOW);
        assertThat(rule.judge(hate, List.of(hate))).isEqualTo(ReportSeverity.CRITICAL);
    }

    @Test
    @DisplayName("three different people reporting the same player raise it a step")
    void pileUp() {
        Report griefing = about(UUID.randomUUID(), "GRIEFING");
        ReportSeverity alone = rule.judge(griefing, List.of(griefing));
        List<Report> three = List.of(griefing, about(UUID.randomUUID(), "STEALING"), about(UUID.randomUUID(), "GRIEFING"));
        assertThat(rule.judge(griefing, three).ordinal()).isEqualTo(alone.ordinal() + 1);
    }

    @Test
    @DisplayName("one person reporting three times is not a pile-up")
    void sameReporter() {
        UUID ayla = UUID.randomUUID();
        Report griefing = about(ayla, "GRIEFING");
        ReportSeverity alone = rule.judge(griefing, List.of(griefing));
        assertThat(rule.judge(griefing, List.of(griefing, about(ayla, "GRIEFING"), about(ayla, "GRIEFING")))).isEqualTo(alone);
    }

    @Test
    @DisplayName("nothing goes above critical")
    void capped() {
        Report hate = about(UUID.randomUUID(), "HATE_SPEECH");
        List<Report> many = List.of(hate, about(UUID.randomUUID(), "THREATS"), about(UUID.randomUUID(), "THREATS"));
        assertThat(rule.judge(hate, many)).isEqualTo(ReportSeverity.CRITICAL);
    }
}
