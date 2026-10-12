package de.raindancer.modules.moderation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPresetTest {

    private static Report report(String text, String preset) {
        return Report.filed("R1", UUID.randomUUID(), "Ayla", UUID.randomUUID(), "Bram", text, Instant.EPOCH)
                .withPreset(preset);
    }

    @Test
    @DisplayName("every group offers presets, and there are many more than the old nine")
    void plenty() {
        for (ReportGroup group : ReportGroup.values()) {
            assertThat(ReportPreset.in(group)).as(group.title()).isNotEmpty();
        }
        assertThat(ReportPreset.values().length).isGreaterThanOrEqualTo(20);
        assertThat(Arrays.stream(ReportPreset.values()).filter(ReportPreset::chat)).isNotEmpty();
    }

    @Test
    @DisplayName("a report filed through a preset knows it")
    void stored() {
        assertThat(ReportPreset.of(report("X-ray — went straight down", "XRAY"))).isEqualTo(ReportPreset.XRAY);
    }

    @Test
    @DisplayName("reports from before presets are read by their category in front")
    void legacy() {
        assertThat(ReportPreset.of(report("Griefing — my house", null))).isEqualTo(ReportPreset.GRIEFING);
        assertThat(ReportPreset.of(report("Harassment — keeps insulting me", null))).isEqualTo(ReportPreset.HARASSMENT);
        assertThat(ReportPreset.of(report("Cheating — flies", null))).isEqualTo(ReportPreset.CHEATING);
        assertThat(ReportPreset.of(report("he took my diamonds", null))).isEqualTo(ReportPreset.SOMETHING_ELSE);
    }

    @Test
    @DisplayName("reports the server files itself are sorted by what found them")
    void automatic() {
        assertThat(ReportPreset.of(report("x-ray: mining this pattern honestly is about one in 10^7", null)))
                .isEqualTo(ReportPreset.XRAY);
        assertThat(ReportPreset.of(report("movement: fly x40, speed x12", null))).isEqualTo(ReportPreset.MOVEMENT_HACKS);
        assertThat(ReportPreset.of(report("combat: reach x9, hitbox x4", null))).isEqualTo(ReportPreset.COMBAT_HACKS);
        assertThat(ReportPreset.of(report("world: nuker x6, fastbreak x6", null))).isEqualTo(ReportPreset.CHEATING);
    }
}
