package de.raindancer.modules.hungergames;

import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Setting;
import de.raindancer.core.data.settings.SettingsSchema;
import org.bukkit.Difficulty;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every default, spelled out by name.
 *
 * <h2>Why this is written out rather than trusted</h2>
 * {@link HungerGamesSettings#DEFAULTS} is a positional constructor with ninety-eight arguments, most of
 * them {@code int} or {@code boolean}. Two swapped components compile perfectly — {@code countdownSeconds}
 * and {@code prepTimePercent} are both {@code int}, and swapping them silently gives every server a
 * thirty-second countdown and a round whose border starts moving after twenty percent of the game. Nothing
 * but a test that names each value by its own accessor can catch that.
 *
 * <p>It is also the record of an upgrade promise. Every value here is what {@code HgSettings} — the
 * catalogue this record replaces — itself shipped, and a server already running the old plugin has a
 * {@code config.yml} written from exactly these defaults. Changing one of them without changing this test
 * is changing what an untouched server does tomorrow.
 */
class HungerGamesSettingsTest {

    private final HungerGamesSettings defaults = HungerGamesSettings.DEFAULTS;

    @Nested
    @DisplayName("the round")
    class Round {

        @Test
        void isWhatItWas() {
            assertThat(defaults.preInitAdmins()).as("pre-init admins").isEmpty();
            assertThat(defaults.gameDurationMinutes()).as("round length").isEqualTo(180);
            assertThat(defaults.gracePeriodSeconds()).as("grace period").isEqualTo(60);
            assertThat(defaults.countdownSeconds()).as("countdown").isEqualTo(20);
            assertThat(defaults.prepTimePercent()).as("prep share").isEqualTo(30);
            assertThat(defaults.gameDifficulty()).as("running difficulty").isEqualTo(Difficulty.NORMAL);
            assertThat(defaults.preflightDifficulty()).as("preflight difficulty")
                    .isEqualTo(Difficulty.PEACEFUL);
            assertThat(defaults.deathAction()).as("on elimination")
                    .isEqualTo(HungerGamesSettings.DeathAction.SPECTATOR);
            assertThat(defaults.disconnectEliminationMinutes()).as("disconnect elimination").isZero();
            assertThat(defaults.offlineTimePolicy()).as("offline policy")
                    .isEqualTo(HungerGamesSettings.OfflineTimePolicy.PAUSE);
        }

        @Test
        void adminParticipantsAreWhatTheyWere() {
            assertThat(defaults.adminDeopOnStart()).isTrue();
            assertThat(defaults.adminReopOnElimination()).isTrue();
            assertThat(defaults.adminReopOnFinish()).isTrue();
            assertThat(defaults.adminCreativeOnElimination()).isTrue();
            assertThat(defaults.adminTeleportCenterOnElimination()).isTrue();
            assertThat(defaults.adminCenterYOffset()).isEqualTo(10);
        }

        @Test
        void theRoundLogIsWhatItWas() {
            assertThat(defaults.roundLogEnabled()).isTrue();
            assertThat(defaults.roundLogFilePerRound()).isTrue();
            assertThat(defaults.roundLogIncludeCoordinates()).isFalse();
        }

        @Test
        @DisplayName("a round shorter than the countdown it must contain cannot happen by accident")
        void theRoundIsClamped() {
            // The explicit failure this guards: a round shorter than five minutes cannot fit its own
            // countdown and grace period, so it is not a shorter tournament, it is one that never
            // visibly starts.
            assertThat(defaults.withGameDurationMinutes(0).roundDuration())
                    .isEqualTo(Duration.ofMinutes(5));
            assertThat(defaults.withGameDurationMinutes(999_999).roundDuration())
                    .isEqualTo(Duration.ofMinutes(20_160));
            assertThat(defaults.roundDuration()).isEqualTo(Duration.ofMinutes(180));
        }

        @Test
        @DisplayName("a negative countdown is nothing to count down, not an instant start")
        void theCountdownIsClamped() {
            assertThat(defaults.withCountdownSeconds(-5).countdown()).isEqualTo(3);
            assertThat(defaults.withCountdownSeconds(9_999).countdown()).isEqualTo(300);
            assertThat(defaults.countdown()).isEqualTo(20);
        }

        @Test
        void gracePeriodAndDisconnectEliminationBecomeDurations() {
            assertThat(defaults.gracePeriod()).isEqualTo(Duration.ofSeconds(60));

            // Zero is not "eliminate instantly" -- it is how the old plugin switched the whole rule off,
            // and that has to survive the read-back rather than being clamped up to some minimum.
            assertThat(defaults.disconnectElimination()).isEqualTo(Duration.ZERO);
        }
    }

    @Nested
    @DisplayName("arena and lobby")
    class ArenaAndLobby {

        @Test
        void arenaIsWhatItWas() {
            assertThat(defaults.platformMinGap()).isEqualTo(4);
            assertThat(defaults.platformWidth()).isEqualTo(3);
            assertThat(defaults.undergroundRoomHeight()).isEqualTo(4);
            assertThat(defaults.undergroundRoomExtraRadius()).isEqualTo(5);
            assertThat(defaults.tubeDepth()).isEqualTo(12);
            assertThat(defaults.blockNetherPortals()).isTrue();
            assertThat(defaults.netherAllowRadius()).isEqualTo(20);
            assertThat(defaults.blockEndPortals()).isTrue();
        }

        @Test
        void lobbyIsWhatItWas() {
            assertThat(defaults.lobbyHeightOffset()).isEqualTo(100);
            assertThat(defaults.lobbyWidth()).isEqualTo(20);
            assertThat(defaults.lobbyDepth()).isEqualTo(20);
            assertThat(defaults.lobbyHeight()).isEqualTo(5);
            assertThat(defaults.lobbyBlockType()).isEqualTo(Material.GLASS);
        }
    }

    @Nested
    @DisplayName("the border")
    class Border {

        @Test
        void isWhatItWas() {
            assertThat(defaults.borderInitialSize()).isEqualTo(2500);
            // Not the old plugin's 100. Fifty is what the tournament this was ported for actually plays
            // to: a hundred-block endgame is a ring two people can lose each other in, and every round
            // there ended on the clock rather than on a winner.
            assertThat(defaults.borderMinimumSize()).isEqualTo(50.0D);
            // Not the old plugin's 2.5. See BorderOutrunTest for the arithmetic that set it: a tribute
            // walled in by stone digs out at about 1.33 blocks per second with an iron pickaxe, and a
            // border faster than that turns a hillside into a death sentence rather than an obstacle.
            assertThat(defaults.borderMaxEdgeSpeed()).isEqualTo(1.25D);
            assertThat(defaults.borderScaleNether()).isTrue();
            assertThat(defaults.borderPrepWarnings()).containsExactly("10", "5", "1");
            assertThat(defaults.borderShrinkWarning()).isEqualTo(30);
        }

        @Test
        @DisplayName("a zero or negative fairness ceiling would break BorderSettings' own constructor")
        void theEdgeSpeedIsClamped() {
            // BorderSettings itself throws on <= 0, but that failure happens when a round tries to
            // start, not when the file was read. This is what stops a hand-edited "max-edge-speed: 0"
            // from ever reaching that constructor.
            assertThat(defaults.withBorderMaxEdgeSpeed(0).borderEdgeSpeed()).isGreaterThan(0.0D);
            assertThat(defaults.withBorderMaxEdgeSpeed(-5).borderEdgeSpeed()).isGreaterThan(0.0D);
            assertThat(defaults.withBorderMaxEdgeSpeed(9_999).borderEdgeSpeed()).isEqualTo(100.0D);
        }

        @Test
        void theFloorIsNeverNegative() {
            assertThat(defaults.borderFloor()).isEqualTo(50.0D);
        }
    }

    @Nested
    @DisplayName("deathmatch")
    class Deathmatch {

        @Test
        void isWhatItWas() {
            assertThat(defaults.deathmatchEnabled()).isTrue();
            assertThat(defaults.deathmatchManualOnly()).isTrue();
            // Matches border.minimum-size deliberately. A deathmatch target above the border's own floor
            // means the border keeps closing past the deathmatch, and one below it is refused by
            // ConfigurationRules — equal is the only value that is neither.
            assertThat(defaults.deathmatchTargetBorderSize()).isEqualTo(50);
            assertThat(defaults.deathmatchWarningSeconds()).isEqualTo(60);
            assertThat(defaults.deathmatchTeleportToCenter()).isFalse();
            assertThat(defaults.deathmatchTeleportYOffset()).isEqualTo(2);
            assertThat(defaults.deathmatchGraceAfterTeleportSeconds()).isEqualTo(10);
            assertThat(defaults.deathmatchRequireConfirmation()).isTrue();
            assertThat(defaults.deathmatchAllowedPhases()).containsExactly("RUNNING");
            assertThat(defaults.deathmatchBroadcastEnabled()).isTrue();
            assertThat(defaults.deathmatchSoundEnabled()).isTrue();
        }

        @Test
        void theGraceAfterTeleportIsClamped() {
            assertThat(defaults.deathmatchGraceAfterTeleport()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        void togglingDeathmatchLeavesEverythingElseAlone() {
            HungerGamesSettings off = defaults.withDeathmatchEnabled(false);

            assertThat(off.deathmatchEnabled()).isFalse();
            assertThat(off).usingRecursiveComparison()
                    .ignoringFields("deathmatchEnabled")
                    .isEqualTo(defaults);
        }
    }

    @Nested
    @DisplayName("supply drops and monster waves")
    class EventsAndMonsters {

        @Test
        void supplyDropsAreWhatTheyWere() {
            assertThat(defaults.supplyDropsEnabled()).isTrue();
            assertThat(defaults.supplyDropWarningSeconds()).isEqualTo(60);
            assertThat(defaults.supplyDropCount()).isEqualTo(1);
            assertThat(defaults.supplyDropRadiusMin()).isEqualTo(30);
            assertThat(defaults.supplyDropRadiusMax()).isEqualTo(250);
            assertThat(defaults.supplyDropOnlyOverworld()).isTrue();
            assertThat(defaults.supplyDropAnnounceCoordinates()).isTrue();
            assertThat(defaults.supplyDropCoordinateFuzz()).isZero();
            assertThat(defaults.supplyDropBeaconEnabled()).isTrue();
            assertThat(defaults.supplyDropBaseMaterial()).isEqualTo(Material.IRON_BLOCK);
            assertThat(defaults.supplyDropProtected()).isTrue();
            assertThat(defaults.supplyDropFireworkEnabled()).isTrue();
            assertThat(defaults.supplyDropParticlesEnabled()).isTrue();
        }

        @Test
        void monsterWavesAreWhatTheyWere() {
            assertThat(defaults.monsterWaveDefaultMob()).isEqualTo("ZOMBIE");
            assertThat(defaults.monsterWaveCountPerWave()).isEqualTo(6);
            assertThat(defaults.monsterWaveWaveCount()).isEqualTo(5);
            assertThat(defaults.monsterWaveIntervalSeconds()).isEqualTo(15);
            assertThat(defaults.monsterWaveSpread()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("gamemasters, loot and protection")
    class GamemastersLootProtection {

        @Test
        void gamemastersAreWhatTheyWere() {
            assertThat(defaults.gamemasterEnabled()).isTrue();
            assertThat(defaults.gamemasterDefaultMode())
                    .isEqualTo(HungerGamesSettings.GamemasterMode.SPECTATOR);
            assertThat(defaults.gamemasterKeepOp()).isTrue();
            assertThat(defaults.gamemasterAllowTeleportMenu()).isTrue();
            assertThat(defaults.gamemasterHideFromPlayerCount()).isTrue();
            assertThat(defaults.gamemasterPermissionMode())
                    .isEqualTo(HungerGamesSettings.GamemasterPermissionMode.PERMISSION);
        }

        @Test
        void lootIsWhatItWas() {
            assertThat(defaults.lootScanRadius()).isEqualTo(50);
            assertThat(defaults.lootScanYRange()).isEqualTo(30);
            assertThat(defaults.lootEditorEnabled()).isTrue();
            assertThat(defaults.lootEditorAllowRuntimeEdits()).isTrue();
            assertThat(defaults.lootEditorBackupBeforeSave()).isTrue();
            assertThat(defaults.lootEditorMaxTestRolls()).isEqualTo(100);
            assertThat(defaults.lootEditorAllowTestGive()).isTrue();
            assertThat(defaults.lootEditorAllowTestChest()).isTrue();
        }

        @Test
        void protectionIsWhatItWas() {
            assertThat(defaults.cornucopiaRadius()).isEqualTo(20);
            assertThat(defaults.protectCornucopiaBeforeRunning()).isTrue();
            assertThat(defaults.protectCornucopiaDuringRunning()).isFalse();
            assertThat(defaults.protectCornucopiaAfterGame()).isFalse();
            assertThat(defaults.protectionBypassPermission())
                    .isEqualTo("hungergames.protection.bypass");
        }
    }

    @Nested
    @DisplayName("announcements")
    class Announcements {

        @Test
        void areWhatTheyWere() {
            assertThat(defaults.announcementsEnabled()).isTrue();
            assertThat(defaults.announceUseChat()).isTrue();
            assertThat(defaults.announceUseTitle()).isTrue();
            assertThat(defaults.announceUseActionbar()).isTrue();
            assertThat(defaults.announceKillfeedEnabled()).isTrue();
            assertThat(defaults.announceRemainingPlayersEnabled()).isTrue();
            assertThat(defaults.announceRemainingPlayersThresholds())
                    .containsExactly("10", "5", "3", "2");
        }
    }

    @Nested
    @DisplayName("the HTTP API")
    class Api {

        @Test
        @DisplayName("off, local-only, no key yet, and writable — the safe shape for a server that has "
                + "never touched config.yml")
        void isWhatItWas() {
            assertThat(defaults.apiEnabled()).as("api.enabled").isFalse();
            assertThat(defaults.apiBindAddress()).as("api.bind-address").isEqualTo("127.0.0.1");
            assertThat(defaults.apiPort()).as("api.port").isEqualTo(8567);
            assertThat(defaults.apiKey()).as("api.key").isEmpty();
            assertThat(defaults.apiReadOnly()).as("api.read-only").isFalse();
        }
    }

    @Nested
    @DisplayName("items")
    class Items {

        @Test
        @DisplayName("every per-item tuning value is what the old plugin shipped, except the two a live "
                + "tournament actually tuned")
        void isWhatItWasExceptWhereATournamentChangedIt() {
            assertThat(defaults.fiendfinderGlowDuration()).as("fiendfinder glow duration").isEqualTo(15);
            assertThat(defaults.fiendfinderSearchRadius()).as("fiendfinder search radius").isZero();

            assertThat(defaults.smokeBombRadius()).as("smoke bomb radius").isEqualTo(6);
            // Not the old plugin's 6 -- a season of real tournaments settled on three seconds, and that
            // tuning is now the shipped default rather than something every restart quietly undid.
            assertThat(defaults.smokeBombEnemyDuration()).as("smoke bomb enemy duration").isEqualTo(3);
            assertThat(defaults.smokeBombInvisSeconds()).as("smoke bomb self-invisibility").isEqualTo(3);

            assertThat(defaults.medikitRegenSeconds()).as("medikit regen seconds").isEqualTo(6);
            assertThat(defaults.medikitRegenLevel()).as("medikit regen level").isEqualTo(2);
            assertThat(defaults.medikitAbsorptionSeconds()).as("medikit absorption seconds").isEqualTo(60);
            assertThat(defaults.medikitAbsorptionLevel()).as("medikit absorption level").isEqualTo(2);
            // Not the old plugin's 3 -- the same live server that tuned the smoke bomb had also tuned this.
            assertThat(defaults.medikitCountdownSeconds()).as("medikit countdown seconds").isEqualTo(2);

            assertThat(defaults.lightningRange()).as("lightning range").isEqualTo(80);
            assertThat(defaults.lightningBoltCount()).as("lightning bolt count").isEqualTo(6);
            assertThat(defaults.lightningSpread()).as("lightning spread").isEqualTo(3);
            assertThat(defaults.lightningBonusDamage()).as("lightning bonus damage").isEqualTo(8);
            assertThat(defaults.lightningDamageRadius()).as("lightning damage radius").isEqualTo(4);
            assertThat(defaults.lightningFireTicks()).as("lightning fire ticks").isEqualTo(80);
            assertThat(defaults.lightningBoltDelay()).as("lightning bolt delay").isEqualTo(3);
            assertThat(defaults.lightningKnockup()).as("lightning knockup").isTrue();

            assertThat(defaults.hermesFlightSeconds()).as("hermes flight seconds").isEqualTo(4);
            assertThat(defaults.hermesWarningSeconds()).as("hermes warning seconds").isEqualTo(3);

            assertThat(defaults.krueckauRadius()).as("krueckau radius").isEqualTo(4);
            assertThat(defaults.krueckauNauseaSeconds()).as("krueckau nausea seconds").isEqualTo(12);
            assertThat(defaults.krueckauBlindnessSeconds()).as("krueckau blindness seconds").isZero();

            assertThat(defaults.auraDurationSeconds()).as("aura duration seconds").isEqualTo(5);
            assertThat(defaults.auraRadius()).as("aura radius").isEqualTo(4);
            assertThat(defaults.auraDamage()).as("aura damage").isEqualTo(6);
            assertThat(defaults.auraInterval()).as("aura interval ticks").isEqualTo(10);
            assertThat(defaults.auraKnockback()).as("aura knockback (stored as tenths)").isEqualTo(6);
            assertThat(defaults.auraAffectMobs()).as("aura affects mobs").isTrue();

            assertThat(defaults.grapplingRange()).as("grappling range").isEqualTo(40);
            assertThat(defaults.grapplingPower()).as("grappling power (stored as tenths)").isEqualTo(14);

            assertThat(defaults.repulseRadius()).as("repulse radius").isEqualTo(6);
            assertThat(defaults.repulseStrength()).as("repulse strength (stored as tenths)").isEqualTo(12);
            assertThat(defaults.repulseSlowSeconds()).as("repulse slow seconds").isEqualTo(2);
            assertThat(defaults.repulseAffectMobs()).as("repulse affects mobs").isTrue();

            assertThat(defaults.feastGoldenApples()).as("feast golden apples").isEqualTo(2);
            assertThat(defaults.warKitMaterial()).as("war kit material").isEqualTo("IRON");
            assertThat(defaults.leapPower()).as("leap power (stored as tenths)").isEqualTo(15);

            assertThat(defaults.exmatrikulatorDuration()).as("exmatrikulator duration").isEqualTo(5);
            assertThat(defaults.exmatrikulatorRadius()).as("exmatrikulator radius").isEqualTo(8);
            assertThat(defaults.exmatrikulatorInterval()).as("exmatrikulator interval ticks").isEqualTo(4);
            assertThat(defaults.exmatrikulatorDamage()).as("exmatrikulator bonus damage").isEqualTo(6);
            assertThat(defaults.exmatrikulatorMaxTargets()).as("exmatrikulator max targets").isEqualTo(5);
            assertThat(defaults.exmatrikulatorFireTicks()).as("exmatrikulator fire ticks").isEqualTo(40);
            assertThat(defaults.exmatrikulatorModules()).as("exmatrikulator modules").hasSize(9);
            assertThat(defaults.exmatrikulatorDeathMessages()).as("exmatrikulator death messages")
                    .hasSize(5);
            assertThat(defaults.exmatrikulatorRecipe()).as("exmatrikulator recipe").hasSize(3);

            assertThat(defaults.stupidnessHealHearts()).as("stupidness heal hearts").isEqualTo(8);
            assertThat(defaults.stupidnessRegenSeconds()).as("stupidness regen seconds").isEqualTo(8);
            assertThat(defaults.stupidnessFireResistSeconds()).as("stupidness fire resistance seconds")
                    .isEqualTo(10);
            assertThat(defaults.stupidnessShoveRadius()).as("stupidness shove radius").isEqualTo(5);
            assertThat(defaults.stupidnessShoveStrength()).as("stupidness shove strength (stored as tenths)")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("every tenths field converts to the decimal it was always meant to be")
        void tenthsConvertToDecimals() {
            assertThat(defaults.auraKnockbackStrength()).as("aura knockback").isEqualTo(0.6D);
            assertThat(defaults.grapplingPowerStrength()).as("grappling power").isEqualTo(1.4D);
            assertThat(defaults.repulseStrengthMultiplier()).as("repulse strength").isEqualTo(1.2D);
            assertThat(defaults.leapPowerStrength()).as("leap power").isEqualTo(1.5D);
            assertThat(defaults.stupidnessShoveStrengthMultiplier()).as("stupidness shove strength")
                    .isEqualTo(1.2D);
        }
    }

    @Nested
    @DisplayName("changing one value")
    class Copies {

        @Test
        void leavesEveryOtherValueExactlyWhereItWas() {
            HungerGamesSettings changed = defaults.withGameDurationMinutes(30);

            assertThat(changed.gameDurationMinutes()).isEqualTo(30);
            assertThat(changed).usingRecursiveComparison()
                    .ignoringFields("gameDurationMinutes")
                    .isEqualTo(defaults);
        }

        @Test
        void theDeathActionCopyLeavesEverythingElseAlone() {
            HungerGamesSettings changed = defaults.withDeathAction(HungerGamesSettings.DeathAction.KICK);

            assertThat(changed.deathAction()).isEqualTo(HungerGamesSettings.DeathAction.KICK);
            assertThat(changed).usingRecursiveComparison()
                    .ignoringFields("deathAction")
                    .isEqualTo(defaults);
        }
    }

    @Nested
    @DisplayName("as a schema Core can read")
    class AsASchema {

        private final SettingsSchema<HungerGamesSettings> schema =
                SettingsSchema.of(HungerGamesSettings.class, HungerGamesSettings.DEFAULTS);

        @Test
        void describesEverySettingInTheRecord() {
            assertThat(schema.settings())
                    .hasSize(HungerGamesSettings.class.getRecordComponents().length);
        }

        @Test
        void hasOneHundredAndNinetyThreeComponents() {
            // 193 of the old plugin's 272 keys. The rest is wording, cues and stored data -- see
            // HungerGamesSettingsMigrationTest for where each of the others went.
            //
            // It was 98 until a live server's own config.yml was read and the difference turned out to be
            // load-bearing: teams.* (7 keys) and sponsors.* (32) had been written off as "not ported in this
            // wave", and that server had tuned nine of them. teams.max-size: 10 would silently have become
            // 2 -- duos in a tournament that plays teams of ten -- and the sponsor shop's whole twelve-entry
            // list would have been replaced by two placeholder potions. The four startup.* keys that time
            // the launch sequence came back for the same reason: the sequence is what a crowd watches.
            //
            // It became 193 when the same story repeated for items.*: fifty-two per-item tuning keys had
            // been written off as "RainsCore content.items (CustomItem)", and that server had tuned two of
            // them -- items.smoke-bomb.enemy-duration and items.medikit.countdown-seconds -- so a restart on
            // the new build would have silently reverted both to the shipped default.
            assertThat(HungerGamesSettings.class.getRecordComponents()).hasSize(193);
        }

        @Test
        void keepsTheConfigPathsAnExistingServerAlreadyHas() {
            List<String> keys = schema.settings().stream().map(Setting::key).toList();

            assertThat(keys)
                    .contains("game.duration")
                    .contains("game.countdown")
                    .contains("game.death-action")
                    .contains("border.max-edge-speed")
                    .contains("border.minimum-size")
                    .contains("deathmatch.allowed-phases")
                    .contains("events.supply-drops.warning-seconds")
                    .contains("events.monster-waves.default-mob")
                    .contains("gamemaster.permission-mode")
                    .contains("loot.editor.max-test-rolls")
                    .contains("protection.bypass-permission")
                    .contains("announcements.remaining-players-thresholds")
                    .contains("api.enabled")
                    .contains("api.bind-address")
                    .contains("api.port")
                    .contains("api.key")
                    .contains("api.read-only");
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
            assertThat(schema.defaults()).isEqualTo(HungerGamesSettings.DEFAULTS);
        }

        @Test
        void everyComponentHasAKeyAndATopic() {
            List<String> unfiled = new ArrayList<>();
            for (RecordComponent component : HungerGamesSettings.class.getRecordComponents()) {
                if (component.getAnnotation(Key.class) == null) {
                    unfiled.add(component.getName() + " has no @Key");
                }
                if (component.getAnnotation(In.class) == null) {
                    unfiled.add(component.getName() + " has no @In");
                }
            }
            assertThat(unfiled).isEmpty();
        }

        @Test
        void everyKeyIsADottedPathNeverACamelCaseName() {
            for (RecordComponent component : HungerGamesSettings.class.getRecordComponents()) {
                assertThat(component.getAnnotation(Key.class).value())
                        .as("%s", component.getName())
                        .matches("[a-z0-9.-]+");
            }
        }
    }
}
