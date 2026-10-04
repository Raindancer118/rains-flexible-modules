package de.raindancer.e2e.speedrun;

import de.raindancer.core.data.settings.Setting;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.modules.speedrun.SpeedrunSettings;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every setting of the lobby and of Manhunt, changed by an admin in game with {@code /settings set},
 * read back, and put back — and where a setting does something a player can see on the spot, that is
 * checked too. The settings whose effect needs a whole run or hunt are checked in the scenarios that
 * play one (see their {@link Covers}).
 */
@Tag("e2e")
class SettingsScenarioTest {

    /** Settings whose in-game effect another scenario shows; here they are only set, read and reset. */
    private static final Set<String> SHOWN_ELSEWHERE = Set.of("late-join", "late-joiner-side", "practice-kit",
            "close-whitelist-on-start", "runner-lives", "hunter-respawn-delay-seconds", "glowing-runners-every-minutes",
            "glowing-runners-seconds", "head-start-per-hunter-seconds", "hunter-head-start-seconds",
            "hud-head-start-bar", "runner-offline-grace-seconds");

    /** A value the setting takes that is not what it has now. */
    static String another(Setting<?> setting) {
        Object now = setting.defaultValue();
        Class<?> type = setting.type();
        if (type == boolean.class || type == Boolean.class) {
            return String.valueOf(!(Boolean) now);
        }
        if (!setting.choices().isEmpty()) {
            return setting.choices().stream().filter(choice -> !choice.equalsIgnoreCase(String.valueOf(now)))
                    .findFirst().orElseThrow();
        }
        if (type == int.class || type == Integer.class) {
            int value = (Integer) now;
            int min = setting.min() == null ? Integer.MIN_VALUE : setting.min();
            int max = setting.max() == null ? Integer.MAX_VALUE : setting.max();
            return String.valueOf(value + 1 <= max ? value + 1 : Math.max(min, value - 1));
        }
        if (type == double.class || type == Double.class) {
            return String.valueOf((Double) now + 1.5);
        }
        return switch (setting.key()) {
            case "world-name" -> "speedrun_e2e";
            case "game-mode" -> "manhunt";
            case "advancement-key" -> "minecraft:story/mine_stone";
            case "seed" -> "77";
            case "seed-pool" -> "1, 2, 3";
            default -> "e2e";
        };
    }

    @Test
    @DisplayName("every setting: set in game, read back, reset — and the ones a player sees on the spot, seen")
    @Covers({"setting:game-mode", "setting:world-name", "setting:advancement-key", "setting:clear-advancements-on-start",
            "setting:death-policy", "setting:require-exit-portal-after-dragon",
            "setting:creeper-spawn-chance-on-break-percent", "setting:charged-creeper-chance-on-break-percent",
            "setting:creeper-spawn-chance-on-container-percent", "setting:charged-creeper-chance-on-container-percent",
            "setting:start-point-set", "setting:start-x", "setting:start-y", "setting:start-z", "setting:start-yaw",
            "setting:start-pitch", "setting:start-block-staff-only", "setting:lobby-protected",
            "setting:lobby-explosions-blocked", "setting:show-timer-to-onlookers", "setting:restart-when-run-ends",
            "setting:restart-after-seconds", "setting:set-time-on-start", "setting:start-time-ticks",
            "setting:bed-explosions-in-nether", "setting:bed-explosions-in-the-end",
            "setting:anchor-explosions-in-overworld", "setting:anchor-explosions-in-the-end",
            "setting:tnt-in-overworld", "setting:tnt-in-nether", "setting:tnt-in-the-end",
            "setting:end-crystals-in-overworld", "setting:end-crystals-in-nether", "setting:end-crystals-in-the-end",
            "setting:breaking-blocks-before-runs", "setting:monsters-hunt-before-runs", "setting:seed-mode",
            "setting:seed", "setting:seed-pool", "setting:pearl-target", "setting:hud-default",
            "setting:split-announcements", "setting:gold-split-celebration", "setting:rank-edited-runs",
            "setting:setup-done", "setting:tracker-cross-world", "setting:tracker-hunter-may-choose",
            "setting:tracker-show-distance", "setting:tracker-refresh-ticks", "setting:tracker-team-compass",
            "setting:tracker-team-compass-item", "setting:tracker-particle-trail", "setting:runner-compass",
            "setting:runner-structure-compass", "setting:side-switching-mid-hunt", "setting:runner-self-join",
            "setting:hunters-fists-only", "setting:start-in-circle", "setting:summary-in-chat",
            "setting:stats-enabled", "setting:balance-max-runners"})
    void everySetting() {
        try (Game game = Game.start("settings")) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            List<Setting<?>> all = new ArrayList<>(SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS).settings());
            all.addAll(SettingsSchema.of(ManhuntSettings.class, ManhuntSettings.DEFAULTS).settings());

            for (Setting<?> setting : all) {
                String value = another(setting);
                ada.runAndExpect("settings set " + setting.key() + " " + value, "is now");
                // Read back as the server shows it: a switch reads "on" or "off".
                String shown = value.equals("true") ? "Now: on" : value.equals("false") ? "Now: off" : value;
                assertThat(game.get(setting.key())).as(setting.key()).containsIgnoringCase(shown);
                ada.runAndExpect("settings reset " + setting.key(), "is back to");
                Game.covered("setting:" + setting.key());
            }

            // What a player sees on the spot.
            // start-block-staff-only: an ordinary player's lobby items lose the start block.
            ada.runAndExpect("settings set start-block-staff-only true", "is now");
            bo.rejoin();
            Game.awaitLobbyItems(bo);
            Await.never("Bo is handed the start block", Duration.ofSeconds(3),
                    () -> bo.carrying(item -> item.tag("speedrun-lobby-item").filter("start"::equals).isPresent()).isPresent());
            ada.runAndExpect("settings reset start-block-staff-only", "is back to");
            // seed-mode and seed: the next world is made from that seed, and the seed page says so.
            // pearl-target and split-announcements show on a run; hud-default decides where a newcomer sees them.
            ada.runAndExpect("settings set hud-default OFF", "is now");
            ada.runAndExpect("settings set split-announcements false", "is now");
            Game.startRun(ada);
            Game.awaitRacingWithoutSidebar(bo);
            Await.never("Bo sees a sidebar with hud-default OFF", Duration.ofSeconds(3), () -> !bo.sidebar().isEmpty());
            assertThat(game.server.errorsFrom("RainsSpeedrun", "RainsCore")).isEmpty();
        }
    }
}
