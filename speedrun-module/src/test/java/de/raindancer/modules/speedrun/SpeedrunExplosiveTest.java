package de.raindancer.modules.speedrun;

import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule behind the explosives settings: which of the four things a player can set off is allowed
 * to go off in which of a run's three dimensions. A matrix, so it is a table of assertions rather
 * than a listener test — {@link SpeedrunExplosivesListenerTest} covers the events that ask it.
 */
class SpeedrunExplosiveTest {

    /** Every explosive allowed everywhere — what a fresh install ships, and what vanilla does. */
    private static SpeedrunSettings vanilla() {
        return SpeedrunSettings.DEFAULTS;
    }

    /**
     * The same, with the ten explosive settings set one by one — named rather than positional, so a
     * reader can see which switch a test is flipping.
     */
    private static SpeedrunSettings with(boolean bedNether, boolean bedEnd,
                                         boolean anchorOverworld, boolean anchorEnd,
                                         boolean tntOverworld, boolean tntNether, boolean tntEnd,
                                         boolean crystalOverworld, boolean crystalNether,
                                         boolean crystalEnd) {
        SpeedrunSettings base = SpeedrunSettings.DEFAULTS;
        return new SpeedrunSettings(base.gameMode(), base.worldName(), base.advancementKey(),
                base.deathPolicy(), base.requireExitPortalAfterDragon(),
                base.creeperSpawnChanceOnBreakPercent(), base.chargedCreeperChanceOnBreakPercent(),
                base.creeperSpawnChanceOnContainerPercent(), base.chargedCreeperChanceOnContainerPercent(),
                base.startPointSet(), base.startX(), base.startY(), base.startZ(), base.startYaw(),
                base.startPitch(), base.startBlockStaffOnly(), base.lobbyProtected(),
                base.lobbyExplosionsBlocked(), base.showTimerToOnlookers(), base.restartWhenRunEnds(),
                base.restartAfterSeconds(), base.setTimeOnStart(), base.startTimeTicks(),
                bedNether, bedEnd, anchorOverworld, anchorEnd, tntOverworld, tntNether, tntEnd,
                crystalOverworld, crystalNether, crystalEnd,
                false, false);
    }

    @Test
    @DisplayName("a fresh install explodes exactly the way Minecraft does")
    void everythingIsAllowedByDefault() {
        for (SpeedrunExplosive explosive : SpeedrunExplosive.values()) {
            for (World.Environment where : World.Environment.values()) {
                assertThat(explosive.allowedIn(vanilla(), where))
                        .as("%s in %s", explosive, where)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("beds can be turned off in the Nether without touching the End")
    void bedsAreSeparatePerDimension() {
        SpeedrunSettings noNetherBeds = with(false, true, true, true, true, true, true, true, true, true);

        assertThat(SpeedrunExplosive.BED.allowedIn(noNetherBeds, World.Environment.NETHER)).isFalse();
        assertThat(SpeedrunExplosive.BED.allowedIn(noNetherBeds, World.Environment.THE_END)).isTrue();
    }

    @Test
    @DisplayName("beds can be turned off in the End without touching the Nether")
    void endBedsAreTheirOwnSwitch() {
        SpeedrunSettings noEndBeds = with(true, false, true, true, true, true, true, true, true, true);

        assertThat(SpeedrunExplosive.BED.allowedIn(noEndBeds, World.Environment.THE_END)).isFalse();
        assertThat(SpeedrunExplosive.BED.allowedIn(noEndBeds, World.Environment.NETHER)).isTrue();
    }

    @Test
    @DisplayName("a bed in the Overworld is nobody's rule — it does not explode there in the first place")
    void overworldBedsAreNotARule() {
        SpeedrunSettings nothingAllowed =
                with(false, false, false, false, false, false, false, false, false, false);

        assertThat(SpeedrunExplosive.BED.allowedIn(nothingAllowed, World.Environment.NORMAL)).isTrue();
    }

    @Test
    @DisplayName("a respawn anchor is the mirror image: Overworld and End, never the Nether")
    void anchorsAreTheMirrorOfBeds() {
        SpeedrunSettings nothingAllowed =
                with(false, false, false, false, false, false, false, false, false, false);

        assertThat(SpeedrunExplosive.RESPAWN_ANCHOR.allowedIn(nothingAllowed, World.Environment.NORMAL))
                .isFalse();
        assertThat(SpeedrunExplosive.RESPAWN_ANCHOR.allowedIn(nothingAllowed, World.Environment.THE_END))
                .isFalse();
        assertThat(SpeedrunExplosive.RESPAWN_ANCHOR.allowedIn(nothingAllowed, World.Environment.NETHER))
                .as("an anchor in the Nether is a bed that works — there is nothing to refuse")
                .isTrue();
    }

    @Test
    @DisplayName("TNT and end crystals are switchable in all three dimensions, each on its own")
    void tntAndCrystalsAreSwitchableEverywhere() {
        SpeedrunSettings onlyNetherTnt = with(true, true, true, true, false, true, false, true, false, true);

        assertThat(SpeedrunExplosive.TNT.allowedIn(onlyNetherTnt, World.Environment.NORMAL)).isFalse();
        assertThat(SpeedrunExplosive.TNT.allowedIn(onlyNetherTnt, World.Environment.NETHER)).isTrue();
        assertThat(SpeedrunExplosive.TNT.allowedIn(onlyNetherTnt, World.Environment.THE_END)).isFalse();
        assertThat(SpeedrunExplosive.END_CRYSTAL.allowedIn(onlyNetherTnt, World.Environment.NORMAL)).isTrue();
        assertThat(SpeedrunExplosive.END_CRYSTAL.allowedIn(onlyNetherTnt, World.Environment.NETHER)).isFalse();
        assertThat(SpeedrunExplosive.END_CRYSTAL.allowedIn(onlyNetherTnt, World.Environment.THE_END)).isTrue();
    }

    @Test
    @DisplayName("a custom dimension is left alone — these settings are about a run's own three")
    void customDimensionsAreNotRefused() {
        SpeedrunSettings nothingAllowed =
                with(false, false, false, false, false, false, false, false, false, false);

        assertThat(SpeedrunExplosive.TNT.allowedIn(nothingAllowed, World.Environment.CUSTOM)).isTrue();
        assertThat(SpeedrunExplosive.TNT.allowedIn(nothingAllowed, null)).isTrue();
    }
}
