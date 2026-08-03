package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.BroadcastScope;
import de.raindancer.modules.claims.model.CostType;
import de.raindancer.modules.claims.rules.FeatureRules;
import de.raindancer.core.data.settings.Setting;
import de.raindancer.core.data.settings.SettingsSchema;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every default, spelled out.
 *
 * <p>This test exists because {@link ClaimSettings#DEFAULTS} is a positional constructor with over fifty
 * arguments, and a positional constructor of fifty arguments is a mis-ordering waiting to happen. Two
 * {@code int}s swapped compiles perfectly and silently changes what a server does — {@code max-vertices} 3 and
 * {@code min-height} 32 is a plugin that refuses every claim anybody draws.
 *
 * <p>It is also the record of an upgrade promise. These are the values the old plugin defaulted to, and an
 * existing server whose {@code config.yml} does not mention a setting has to behave tomorrow the way it behaved
 * yesterday. A changed default is a silent change to every server that never had an opinion, so changing one
 * of these should mean changing a test and thinking about it.
 */
class ClaimSettingsTest {

    private final ClaimSettings defaults = ClaimSettings.DEFAULTS;

    @Nested
    @DisplayName("the limits")
    class Limits {

        @Test
        void areWhatTheyWere() {
            assertThat(defaults.maxClaimsDefault()).isEqualTo(3);
            assertThat(defaults.minClaimArea()).isEqualTo(9);
            assertThat(defaults.maxClaimArea()).isEqualTo(-1L);
            assertThat(defaults.maxVertices()).isEqualTo(32);
            assertThat(defaults.minClaimHeight()).isEqualTo(3);
            assertThat(defaults.allowOverlappingWorldsOnly()).isTrue();
        }

        @Test
        void noLimitMeansNoLimitRatherThanNoArea() {
            // -1 is "unlimited", and reading it as a maximum would refuse every claim on the server.
            assertThat(defaults.areaIsAllowed(1_000_000_000L)).isTrue();
        }

        @Test
        void tooSmallIsStillRefusedWithNoUpperLimit() {
            assertThat(defaults.areaIsAllowed(4L)).isFalse();
            assertThat(defaults.areaIsAllowed(9L)).isTrue();
        }

        @Test
        void anUpperLimitIsHonouredWhenThereIsOne() {
            ClaimSettings bounded = ClaimSettings.DEFAULTS.withMaxClaimArea(100L);

            assertThat(bounded.areaIsAllowed(100L)).isTrue();
            assertThat(bounded.areaIsAllowed(101L)).isFalse();
        }
    }

    @Nested
    @DisplayName("what a claim costs")
    class Cost {

        @Test
        void isNothingUntilSomebodySaysOtherwise() {
            assertThat(defaults.creationCostType()).isEqualTo(CostType.NONE);
            assertThat(defaults.creationCostAmount()).isEqualTo(1);
            assertThat(defaults.creationCostPerBlock()).isFalse();
            assertThat(defaults.creationCostBlocksPerUnit()).isEqualTo(256);
            assertThat(defaults.refundOnDelete()).isFalse();
            assertThat(defaults.shrinkRefundRate()).isEqualTo(1.0D);
            assertThat(defaults.chargeOnGrow()).isTrue();
        }

        @Test
        void theRefundRateIsClampedSoAFileCannotPayOutMoreThanWasTaken() {
            ClaimSettings generous = ClaimSettings.DEFAULTS.withShrinkRefundRate(4.0D);

            assertThat(generous.refundRate()).isEqualTo(1.0D);
        }
    }

    @Nested
    @DisplayName("entry fees, fences and perks")
    class FeatureRules {

        @Test
        void entryFeesAreWhatTheyWere() {
            assertThat(defaults.entryFeeMaxAmount()).isEqualTo(64);
            assertThat(defaults.entryFeeDeclineCooldownSeconds()).isEqualTo(10);
            assertThat(defaults.entryFeePromptTimeoutSeconds()).isEqualTo(30);
            assertThat(defaults.entryFeeExemptTrusted()).isTrue();
            assertThat(defaults.entryFeeExemptAdmins()).isFalse();
        }

        @Test
        void fencesAreWhatTheyWere() {
            assertThat(defaults.fenceAutoBuild()).isFalse();
            assertThat(defaults.fenceChargeMaterial()).isTrue();
            assertThat(defaults.fenceDefaultMaterial()).isEqualTo(Material.OAK_FENCE);
            assertThat(defaults.fenceHeight()).isEqualTo(1);
            assertThat(defaults.fenceMaxColumns()).isEqualTo(2048);
            assertThat(defaults.fenceMaxStep()).isEqualTo(4);
            assertThat(defaults.fenceRefundToBank()).isTrue();
        }

        @Test
        void perksAreWhatTheyWere() {
            assertThat(defaults.maxClaimEffects()).isEqualTo(3);
            assertThat(defaults.maxEffectAmplifier()).isEqualTo(1);
            assertThat(defaults.effectsRequirePotions()).isFalse();
            assertThat(defaults.effectPotionMinutes()).isEqualTo(30);
            assertThat(defaults.potionStoreMaxStacks()).isEqualTo(270);
            assertThat(defaults.claimThunderBolts()).isTrue();
            assertThat(defaults.maxEquipRules()).isEqualTo(8);
            assertThat(defaults.equipmentMaxStacks()).isEqualTo(270);
            assertThat(defaults.pantryMaxStacks()).isEqualTo(270);
        }

        @Test
        void announcementsAreWhatTheyWere() {
            assertThat(defaults.broadcastNearbyRadius()).isEqualTo(96);
            assertThat(defaults.broadcastScope()).isEqualTo(BroadcastScope.CLAIM);
            assertThat(defaults.broadcastKick()).isTrue();
            assertThat(defaults.broadcastBan()).isTrue();
            assertThat(defaults.broadcastTimeout()).isTrue();
            assertThat(defaults.broadcastLift()).isTrue();
        }
    }

    @Nested
    @DisplayName("appearance and selection")
    class Looks {

        @Test
        void noticesAreWhatTheyWere() {
            // The one deliberate departure from the old plugin's defaults, and it is written down here rather
            // than just changed: a border notice matters for the second it is shown and then never again, so
            // chat is the wrong place for it — three claims on the way home is a wall of messages pushing real
            // conversation off the screen. The config key is unchanged, so a server that already chose keeps
            // its choice and only servers with no opinion see this.
            assertThat(defaults.enterMessageActionBar()).isTrue();
            assertThat(defaults.borderOnEnterSeconds()).isEqualTo(4);
            assertThat(defaults.notificationCooldownSeconds()).isEqualTo(3);
        }

        @Test
        void bordersAreWhatTheyWere() {
            assertThat(defaults.visualDurationSeconds()).isEqualTo(12);
            assertThat(defaults.visualRadius()).isEqualTo(48);
            assertThat(defaults.visualSpacing()).isEqualTo(2);
            assertThat(defaults.visualMaxPointsPerTick()).isEqualTo(600);
            assertThat(defaults.visualShowVerticalPillars()).isTrue();
            assertThat(defaults.visualMode()).isEqualTo(ClaimSettings.VisualMode.PARTICLES);
            assertThat(defaults.visualEdgeBlock()).isEqualTo(Material.GOLD_BLOCK);
            assertThat(defaults.visualCornerBlock()).isEqualTo(Material.GLOWSTONE);
            assertThat(defaults.visualZoneBlock()).isEqualTo(Material.REDSTONE_BLOCK);
        }

        @Test
        void selectionIsWhatItWas() {
            assertThat(defaults.selectionStickMaterial()).isEqualTo(Material.STICK);
            assertThat(defaults.selectionMarkerBlock()).isEqualTo(Material.SEA_LANTERN);
            assertThat(defaults.selectionStickGlint()).isTrue();
            assertThat(defaults.verticalPaddingDown()).isEqualTo(8);
            assertThat(defaults.verticalPaddingUp()).isEqualTo(16);
            assertThat(defaults.allowUndergroundClaims()).isTrue();
            assertThat(defaults.hiddenUndergroundNotificationsMuted()).isTrue();
            assertThat(defaults.verticalMode())
                    .isEqualTo(ClaimSettings.VerticalMode.SELECTION_PADDED);
        }

        @Test
        void housekeepingIsWhatItWas() {
            assertThat(defaults.autoSaveSeconds()).isEqualTo(300);
            assertThat(defaults.debug()).isFalse();
        }
    }

    @Nested
    @DisplayName("changing one value")
    class Copies {

        @Test
        void leavesEveryOtherValueExactlyWhereItWas() {
            // A 62-argument copy constructor with two components swapped compiles and passes any test that
            // only checks the value it was asked to change. This is the test that does not.
            ClaimSettings changed = ClaimSettings.DEFAULTS.withMaxClaimArea(500L);

            assertThat(changed.maxClaimArea()).isEqualTo(500L);
            assertThat(changed).usingRecursiveComparison()
                    .ignoringFields("maxClaimArea")
                    .isEqualTo(ClaimSettings.DEFAULTS);
        }

        @Test
        void theRefundRateCopyLeavesEverythingElseAloneToo() {
            ClaimSettings changed = ClaimSettings.DEFAULTS.withShrinkRefundRate(0.5D);

            assertThat(changed.shrinkRefundRate()).isEqualTo(0.5D);
            assertThat(changed).usingRecursiveComparison()
                    .ignoringFields("shrinkRefundRate")
                    .isEqualTo(ClaimSettings.DEFAULTS);
        }
    }

    @Nested
    @DisplayName("as a schema Core can read")
    class AsASchema {

        private final SettingsSchema<ClaimSettings> schema =
                SettingsSchema.of(ClaimSettings.class, ClaimSettings.DEFAULTS);

        @Test
        void describesEverySettingInTheRecord() {
            assertThat(schema.settings()).hasSize(ClaimSettings.class.getRecordComponents().length);
        }

        @Test
        void keepsTheConfigPathsAnExistingServerAlreadyHas() {
            // The upgrade promise. If these move, somebody's max-claims-per-player quietly becomes 3 again
            // and everybody on the server gets more claims than the owner meant them to have.
            List<String> keys = schema.settings().stream().map(Setting::key).toList();

            assertThat(keys)
                    .contains("limits.max-claims-per-player")
                    .contains("limits.max-area-blocks")
                    .contains("creation-cost.type")
                    .contains("creation-cost.shrink-refund-rate")
                    .contains("entry-fee.exempt-trusted")
                    .contains("fence.default-material")
                    .contains("effects.potion-store-max-stacks")
                    .contains("broadcasts.nearby-radius")
                    .contains("notifications.use-action-bar")
                    .contains("visualisation.corner-pillars")
                    .contains("selection.mute-notifications-for-hidden-claims")
                    .contains("visualisation.selection-marker-block")
                    .contains("selection.vertical-mode")
                    .contains("visualisation.mode")
                    .contains("storage.auto-save-seconds")
                    .contains("debug");
        }

        @Test
        void givesEverySettingATopicThatExists() {
            for (Setting<?> setting : schema.settings()) {
                assertThat(schema.topics().at(setting.topicPath()))
                        .as("%s is filed under '%s', which is not a topic this record declares",
                                setting.key(), setting.topicPath())
                        .isPresent();
            }
        }

        @Test
        void hasNoTwoSettingsSharingAKey() {
            List<String> keys = schema.settings().stream().map(Setting::key).toList();
            assertThat(keys).doesNotHaveDuplicates();
        }

        @Test
        void readsItsOwnDefaultsBack() {
            assertThat(schema.defaults()).isEqualTo(ClaimSettings.DEFAULTS);
        }
    }
}
