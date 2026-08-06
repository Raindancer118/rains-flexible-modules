package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.BorderService;
import de.raindancer.modules.hungergames.service.DeathmatchService;
import de.raindancer.modules.hungergames.service.VirtualTime;
import de.raindancer.modules.hungergames.store.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeathmatchServiceTest {

    private GameSession session;
    private BorderService.WorldBorderTarget target;
    private BorderService border;
    private DeathmatchService deathmatch;
    private int teleportCalls;

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(0));
        target = new BorderService.WorldBorderTarget() {
            double size = 2500;
            @Override public double currentSize() { return size; }
            @Override public void shrinkOverworld(double targetSize, long ticks) { size = targetSize; }
            @Override public void shrinkNether(double targetSize, long ticks) { }
            @Override public void resetTo(double overworldSize) { size = overworldSize; }
        };
        border = new BorderService(session, new VirtualTime(), target);
        border.settings(HungerGamesSettings.DEFAULTS);
        deathmatch = new DeathmatchService(session, border, settings -> teleportCalls++);
        deathmatch.settings(HungerGamesSettings.DEFAULTS.withDeathmatchEnabled(true));

        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);
    }

    @Test
    void startIsRefusedWhenDisabled() {
        deathmatch.settings(HungerGamesSettings.DEFAULTS.withDeathmatchEnabled(false));
        assertThat(deathmatch.start()).isPresent();
        assertThat(deathmatch.state()).isEqualTo(DeathmatchService.State.IDLE);
    }

    @Test
    void startMovesToWarningThenExecuteMovesToActive() {
        assertThat(deathmatch.start()).isEmpty();
        assertThat(deathmatch.state()).isEqualTo(DeathmatchService.State.WARNING);

        deathmatch.execute();
        assertThat(deathmatch.state()).isEqualTo(DeathmatchService.State.ACTIVE);
        assertThat(target.currentSize())
                .isEqualTo(HungerGamesSettings.DEFAULTS.deathmatchTargetBorderSize());
    }

    @Test
    void startRefusedOutsideThePhaseWhitelist() {
        HungerGamesSettings restricted = HungerGamesSettings.DEFAULTS.withDeathmatchEnabled(true);
        // RUNNING is in DEFAULTS' allowed-phases list; move somewhere it is not.
        session.declareTimeout();
        deathmatch.settings(restricted);
        assertThat(deathmatch.start()).isPresent();
    }

    @Test
    void cannotStartTwice() {
        deathmatch.start();
        assertThat(deathmatch.start()).isPresent();
    }

    @Test
    void cancelOnlyWorksDuringWarning() {
        assertThat(deathmatch.cancel()).isPresent(); // nothing running yet

        deathmatch.start();
        assertThat(deathmatch.cancel()).isEmpty();
        assertThat(deathmatch.state()).isEqualTo(DeathmatchService.State.IDLE);

        deathmatch.start();
        deathmatch.execute();
        assertThat(deathmatch.cancel()).isPresent(); // ACTIVE cannot be cancelled
    }

    @Test
    void teleportOnlyHappensWhenConfigured() {
        deathmatch.settings(HungerGamesSettings.DEFAULTS.withDeathmatchEnabled(true));
        deathmatch.start();
        deathmatch.execute();
        assertThat(teleportCalls).isEqualTo(HungerGamesSettings.DEFAULTS.deathmatchTeleportToCenter() ? 1 : 0);
    }

    @Test
    void automaticTriggerOnlyWhenManualOnlyIsOffAndTwoRemain() {
        HungerGamesSettings manual = withManualOnly(true);
        deathmatch.settings(manual);
        assertThat(deathmatch.autoTriggerReason(2)).isFalse();

        deathmatch.settings(withManualOnly(false));
        assertThat(deathmatch.autoTriggerReason(3)).isFalse();
        assertThat(deathmatch.autoTriggerReason(2)).isTrue();

        deathmatch.start();
        assertThat(deathmatch.autoTriggerReason(2)).isFalse(); // no longer IDLE
    }

    @Test
    void restoreActiveReassertsTheBorderOverrideWithoutTeleporting() {
        deathmatch.restoreActive();
        assertThat(deathmatch.state()).isEqualTo(DeathmatchService.State.ACTIVE);
        assertThat(target.currentSize())
                .isEqualTo(HungerGamesSettings.DEFAULTS.deathmatchTargetBorderSize());
        assertThat(teleportCalls).isZero();
    }

    @Test
    void resetForNewRoundAlwaysReturnsToIdle() {
        deathmatch.start();
        deathmatch.execute();
        deathmatch.resetForNewRound();
        assertThat(deathmatch.state()).isEqualTo(DeathmatchService.State.IDLE);
    }

    private static HungerGamesSettings withManualOnly(boolean manualOnly) {
        HungerGamesSettings base = HungerGamesSettings.DEFAULTS.withDeathmatchEnabled(true);
        return rebuild(base, manualOnly);
    }

    /**
     * A local copy-with, so this test does not need a ninety-component positional constructor call spelt
     * out at every call site. {@code HungerGamesSettings} itself only carries {@code with…} for the handful
     * of components a settings screen exposes directly; {@code manualOnly} is not one of them yet.
     */
    private static HungerGamesSettings rebuild(HungerGamesSettings s, boolean manualOnly) {
        return new HungerGamesSettings(
                s.preInitAdmins(), s.gameDurationMinutes(), s.gracePeriodSeconds(), s.countdownSeconds(),
                s.prepTimePercent(), s.gameDifficulty(), s.preflightDifficulty(), s.deathAction(),
                s.disconnectEliminationMinutes(), s.offlineTimePolicy(), s.adminDeopOnStart(),
                s.adminReopOnElimination(), s.adminReopOnFinish(), s.adminCreativeOnElimination(),
                s.adminTeleportCenterOnElimination(), s.adminCenterYOffset(), s.roundLogEnabled(),
                s.roundLogFilePerRound(), s.roundLogIncludeCoordinates(), s.platformMinGap(),
                s.platformWidth(), s.undergroundRoomHeight(), s.undergroundRoomExtraRadius(),
                s.tubeDepth(), s.blockNetherPortals(), s.netherAllowRadius(), s.blockEndPortals(),
                s.startupLampDelay(), s.startupLevitationStartDelay(), s.startupPlayerLevitationDelay(),
                s.startupLevitationAmplifier(), s.lobbyHeightOffset(), s.lobbyWidth(), s.lobbyDepth(),
                s.lobbyHeight(), s.lobbyBlockType(), s.borderInitialSize(), s.borderMinimumSize(),
                s.borderMaxEdgeSpeed(), s.borderScaleNether(), s.borderPrepWarnings(),
                s.borderShrinkWarning(), s.deathmatchEnabled(), manualOnly, s.deathmatchTargetBorderSize(),
                s.deathmatchWarningSeconds(), s.deathmatchTeleportToCenter(),
                s.deathmatchTeleportYOffset(), s.deathmatchGraceAfterTeleportSeconds(),
                s.deathmatchRequireConfirmation(), s.deathmatchAllowedPhases(),
                s.deathmatchBroadcastEnabled(), s.deathmatchSoundEnabled(), s.supplyDropsEnabled(),
                s.supplyDropWarningSeconds(), s.supplyDropCount(), s.supplyDropRadiusMin(),
                s.supplyDropRadiusMax(), s.supplyDropOnlyOverworld(), s.supplyDropAnnounceCoordinates(),
                s.supplyDropCoordinateFuzz(), s.supplyDropBeaconEnabled(), s.supplyDropBaseMaterial(),
                s.supplyDropProtected(), s.supplyDropFireworkEnabled(), s.supplyDropParticlesEnabled(),
                s.monsterWaveDefaultMob(), s.monsterWaveCountPerWave(), s.monsterWaveWaveCount(),
                s.monsterWaveIntervalSeconds(), s.monsterWaveSpread(), s.gamemasterEnabled(),
                s.gamemasterDefaultMode(), s.gamemasterKeepOp(), s.gamemasterAllowTeleportMenu(),
                s.gamemasterHideFromPlayerCount(), s.gamemasterPermissionMode(), s.teamMaxSize(),
                s.teamMaxTeams(), s.teamAllowSwitching(), s.teamCaptainEnabled(), s.teamPlayersCanCreate(),
                s.teamPlayersChooseColour(), s.teamLockPhase(), s.sponsorsEnabled(),
                s.sponsorTokensEnabled(), s.sponsorTokenMaterial(), s.sponsorTokenName(),
                s.sponsorTokenLore(), s.sponsorTokenIntervalMinutes(), s.sponsorTokenAmountPerInterval(),
                s.sponsorTokenFirstAfterMinutes(), s.sponsorTokenMaxPerPlayer(), s.sponsorTokenOnlyAlive(),
                s.sponsorTokenAnnouncePersonal(), s.sponsorTokenBroadcastMilestones(),
                s.sponsorTokenDropOnDeath(), s.sponsorTokenClearOnElimination(),
                s.sponsorTokenClearOnRoundReset(), s.sponsorBeaconsEnabled(), s.sponsorBeaconSpawnMode(),
                s.sponsorBeaconCentreOnStart(), s.sponsorBeaconMaterial(), s.sponsorBeaconBaseMaterial(),
                s.sponsorBeaconProtected(), s.sponsorBeaconRadiusMin(), s.sponsorBeaconRadiusMax(),
                s.sponsorBeaconSchedule(), s.sponsorBeaconMaxActive(), s.sponsorBeaconAnnounceSpawn(),
                s.sponsorBeaconAnnounceCoordinates(), s.sponsorBeaconCoordinateFuzz(),
                s.sponsorBeaconParticles(), s.sponsorBeaconSound(), s.sponsorShopEnabled(),
                s.sponsorShopItems(), s.lootScanRadius(), s.lootScanYRange(), s.lootEditorEnabled(),
                s.lootEditorAllowRuntimeEdits(), s.lootEditorBackupBeforeSave(),
                s.lootEditorMaxTestRolls(), s.lootEditorAllowTestGive(), s.lootEditorAllowTestChest(),
                s.cornucopiaRadius(), s.protectCornucopiaBeforeRunning(),
                s.protectCornucopiaDuringRunning(), s.protectCornucopiaAfterGame(),
                s.protectionBypassPermission(), s.announcementsEnabled(), s.announceUseChat(),
                s.announceUseTitle(), s.announceUseActionbar(), s.announceKillfeedEnabled(),
                s.announceRemainingPlayersEnabled(), s.announceRemainingPlayersThresholds(),
                s.apiEnabled(), s.apiBindAddress(), s.apiPort(), s.apiKey(), s.apiReadOnly(),
                s.fiendfinderGlowDuration(), s.fiendfinderSearchRadius(), s.smokeBombRadius(),
                s.smokeBombEnemyDuration(), s.smokeBombInvisSeconds(), s.medikitRegenSeconds(),
                s.medikitRegenLevel(), s.medikitAbsorptionSeconds(), s.medikitAbsorptionLevel(),
                s.medikitCountdownSeconds(), s.lightningRange(), s.lightningBoltCount(),
                s.lightningSpread(), s.lightningBonusDamage(), s.lightningDamageRadius(),
                s.lightningFireTicks(), s.lightningBoltDelay(), s.lightningKnockup(),
                s.hermesFlightSeconds(), s.hermesWarningSeconds(), s.krueckauRadius(),
                s.krueckauNauseaSeconds(), s.krueckauBlindnessSeconds(), s.auraDurationSeconds(),
                s.auraRadius(), s.auraDamage(), s.auraInterval(), s.auraKnockback(), s.auraAffectMobs(),
                s.grapplingRange(), s.grapplingPower(), s.repulseRadius(), s.repulseStrength(),
                s.repulseSlowSeconds(), s.repulseAffectMobs(), s.feastGoldenApples(), s.warKitMaterial(),
                s.leapPower(), s.exmatrikulatorDuration(), s.exmatrikulatorRadius(),
                s.exmatrikulatorInterval(), s.exmatrikulatorDamage(), s.exmatrikulatorMaxTargets(),
                s.exmatrikulatorFireTicks(), s.exmatrikulatorModules(), s.exmatrikulatorDeathMessages(),
                s.exmatrikulatorRecipe(), s.stupidnessHealHearts(), s.stupidnessRegenSeconds(),
                s.stupidnessFireResistSeconds(), s.stupidnessShoveRadius(), s.stupidnessShoveStrength());
    }
}
