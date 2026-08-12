package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.ApproachReading;
import de.raindancer.modules.moderation.model.MinedBlock;
import de.raindancer.modules.moderation.rules.XrayRule;
import de.raindancer.modules.moderation.store.PersistedFindings;
import de.raindancer.modules.moderation.store.PlayerMiningProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Watching everybody's mining, and remembering enough of it to be reviewed afterwards.
 *
 * <h2>Why {@link ReportService} is a mock here rather than a real one</h2>
 * It needs a plugin, a server and an audit trail to construct, none of which this class's own job —
 * turning block-by-block events into a ratio and a remembered trail — has any opinion about. What
 * matters here is whether a report gets filed at all and, separately, what {@link #approachesFor} can
 * later say about the same mining — not how a report is stored.
 */
class XrayDetectionServiceTest {

    private static final UUID MOD = UUID.randomUUID();

    @TempDir
    Path folder;

    /**
     * A profile store backed by a fresh temporary folder per test — nothing here ever calls
     * {@code load()} or {@code flush()}, so this never actually touches the disk, but the store
     * still needs a real path to be built against.
     */
    private XrayDetectionService newService(ReportService reports, ModerationSettings settings) {
        return new XrayDetectionService(reports, new XrayRule(), new PlayerMiningProfiles(folder),
                new PersistedFindings(folder), settings);
    }

    private static ModerationSettings settingsWith(boolean xrayEnabled, int windowBlocks,
                                                    int minimumOre, int thresholdPercent) {
        return ModerationSettings.DEFAULTS
                .withXrayDetectionEnabled(xrayEnabled)
                .withXrayWindowBlocks(windowBlocks)
                .withXrayMinimumOre(minimumOre)
                .withXrayThresholdPercent(thresholdPercent)
                .withXrayOres(List.of("DIAMOND_ORE"))
                .withXrayLearningEnabled(false);
    }

    private static MinedBlock stone(int x) {
        return new MinedBlock("world", x, 64, 0, "STONE");
    }

    private static MinedBlock diamond(int x) {
        return new MinedBlock("world", x, 64, 0, "DIAMOND_ORE");
    }

    @Nested
    @DisplayName("filing a report")
    class Filing {

        @Test
        @DisplayName("a ratio past the threshold files exactly one report")
        void filesWhenSuspicious() {
            ReportService reports = mock(ReportService.class);
            // One stone, one diamond: a 50% ratio, at a 40% threshold with the minimum already met.
            XrayDetectionService service = newService(reports, settingsWith(true, 20, 1, 40));

            service.mined(MOD, "Mod", stone(0));
            service.mined(MOD, "Mod", diamond(1));

            verify(reports, times(1)).file(any(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("switched off, nothing is ever filed, whatever is mined")
        void doesNothingWhenDisabled() {
            ReportService reports = mock(ReportService.class);
            XrayDetectionService service = newService(reports, settingsWith(false, 20, 1, 1));

            for (int i = 0; i < 20; i++) {
                service.mined(MOD, "Mod", diamond(i));
            }

            verify(reports, never()).file(any(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("a null player is not an error, and files nothing")
        void nullPlayerIsHarmless() {
            ReportService reports = mock(ReportService.class);
            XrayDetectionService service = newService(reports, settingsWith(true, 20, 1, 1));

            service.mined(null, "Nobody", diamond(0));

            verify(reports, never()).file(any(), any(), any(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("reviewing what was actually mined")
    class Reviewing {

        @Test
        @DisplayName("survives a restart — a fresh service reading the same folder sees it too")
        void survivesARestart() {
            ReportService reports = mock(ReportService.class);
            XrayDetectionService beforeRestart = newService(reports, settingsWith(true, 200, 1, 100));
            beforeRestart.mined(MOD, "Mod", stone(0));
            beforeRestart.mined(MOD, "Mod", diamond(1));
            beforeRestart.flush();

            // Nothing about this reuses beforeRestart — a brand new service, over new in-memory
            // windows and trails, is exactly what actually happens when the plugin reloads.
            XrayDetectionService afterRestart = newService(mock(ReportService.class),
                    settingsWith(true, 200, 1, 100));
            afterRestart.load();

            assertThat(afterRestart.approachesFor(MOD)).hasSize(1);
            assertThat(afterRestart.probabilityFor(MOD))
                    .as("the probability the review screen is shown alongside must survive too")
                    .isEqualTo(beforeRestart.probabilityFor(MOD));
        }

        @Test
        @DisplayName("an ore block with a straight approach behind it shows up as highly direct")
        void remembersTheApproach() {
            ReportService reports = mock(ReportService.class);
            XrayDetectionService service = newService(reports, settingsWith(true, 200, 100, 100));

            for (int i = 0; i < 10; i++) {
                service.mined(MOD, "Mod", stone(i));
            }
            service.mined(MOD, "Mod", diamond(10));

            List<ApproachReading> readings = service.approachesFor(MOD);

            assertThat(readings).hasSize(1);
            assertThat(readings.getFirst().directnessPercent()).isEqualTo(100);
        }

        @Test
        @DisplayName("still remembered even when the ratio never crossed the report threshold")
        void remembersEvenWithoutAReport() {
            // The threshold is set well out of reach, so no report is ever filed — the trail is a
            // separate memory from the ratio-driven report, kept for exactly this: a human deciding
            // to look even though the automatic watcher never flagged anything at all.
            ReportService reports = mock(ReportService.class);
            XrayDetectionService service = newService(reports, settingsWith(true, 200, 1, 100));

            service.mined(MOD, "Mod", stone(0));
            service.mined(MOD, "Mod", diamond(1));

            verify(reports, never()).file(any(), any(), any(), any(), anyString());
            assertThat(service.approachesFor(MOD)).hasSize(1);
        }

        @Test
        @DisplayName("somebody nobody has watched mining has nothing to show")
        void unknownPlayerHasNoHistory() {
            XrayDetectionService service = newService(mock(ReportService.class), settingsWith(true, 200, 1, 1));

            assertThat(service.approachesFor(UUID.randomUUID())).isEmpty();
            assertThat(service.approachesFor(null)).isEmpty();
        }

        @Test
        @DisplayName("switched off, nothing is remembered either")
        void disabledRemembersNothing() {
            XrayDetectionService service = newService(mock(ReportService.class), settingsWith(false, 200, 1, 1));

            service.mined(MOD, "Mod", diamond(0));

            assertThat(service.approachesFor(MOD))
                    .as("a server that switched detection off entirely should not still be building "
                            + "a file on people in the background")
                    .isEmpty();
        }

        @Test
        @DisplayName("leaving forgets the session's ratio window, but not what was already found")
        void forgettingKeepsWhatWasAlreadyFound() {
            // The window and the live trail are session-only and cost nothing to lose on a quit —
            // see MiningWindow and MiningTrail's own notes. What has already been turned into a
            // finding is not: forgetting a player the moment they disconnect is exactly the wrong
            // time to lose the one thing a moderator might come back tomorrow to read.
            ReportService reports = mock(ReportService.class);
            XrayDetectionService service = newService(reports, settingsWith(true, 200, 1, 100));
            service.mined(MOD, "Mod", stone(0));
            service.mined(MOD, "Mod", diamond(1));

            service.forget(MOD);

            assertThat(service.approachesFor(MOD)).hasSize(1);
        }
    }
}
