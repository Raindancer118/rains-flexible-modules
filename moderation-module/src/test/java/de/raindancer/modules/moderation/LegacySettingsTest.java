package de.raindancer.modules.moderation;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** What an owner set in the separate anti-cheat plugin before 2.0 is what the moderation module's anti-cheat part uses. */
class LegacySettingsTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("every value the owner changed comes along; untouched ones stay at the defaults")
    void carriedOver() throws Exception {
        Path old = folder.resolve("RainsAntiCheat").resolve("config.yml");
        Files.createDirectories(old.getParent());
        Files.writeString(old, """
                auto-kick: true
                punish-scale: 150
                blocked-brands:
                  - wurst
                  - impact
                anti-esp: true
                reach-leniency: 0.5
                """);
        SettingsStore<AntiCheatSettings> before = new SettingsStore<>(
                SettingsSchema.of(AntiCheatSettings.class, AntiCheatSettings.DEFAULTS), old);
        before.load();
        SettingsStore<ModerationSettings> now = new SettingsStore<>(
                SettingsSchema.of(ModerationSettings.class, ModerationSettings.DEFAULTS),
                folder.resolve("RainsModeration").resolve("config.yml"));
        now.load();

        int moved = LegacySettings.carryOver(before, now);

        AntiCheatSettings merged = now.current().antiCheat();
        assertThat(merged.autoKick()).isTrue();
        assertThat(merged.punishScale()).isEqualTo(150);
        assertThat(merged.blockedBrands()).containsExactly("wurst", "impact");
        assertThat(merged.antiEsp()).isTrue();
        assertThat(merged.alerts()).as("untouched").isEqualTo(AntiCheatSettings.DEFAULTS.alerts());
        assertThat(moved).isEqualTo(4);
    }
}
