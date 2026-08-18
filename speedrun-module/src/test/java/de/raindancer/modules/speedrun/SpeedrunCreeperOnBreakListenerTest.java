package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirrors {@link SpeedrunOccupancyListenerTest}'s idiom: real events built directly, a real
 * {@link SpeedrunSession} rather than a mock, and only reacting — deciding nothing about whether the
 * break itself proceeds.
 */
class SpeedrunCreeperOnBreakListenerTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @TempDir
    Path dataFolder;

    private SettingsStore<SpeedrunSettings> settings;
    private World world;
    private Block block;

    @BeforeEach
    void setUp() {
        settings = new SettingsStore<>(
                SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                dataFolder.resolve("speedrun.yml"));
        settings.load();

        world = mock(World.class);
        block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(new Location(world, 10, 64, 10));
    }

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private BlockBreakEvent breakEventBy(UUID breaker) {
        return new BlockBreakEvent(block, playerWithId(breaker));
    }

    @Test
    @DisplayName("spawns a creeper where a participant breaks a block during the run")
    void spawnsCreeperOnParticipantBreak() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
        session.start();
        SpeedrunCreeperOnBreakListener listener = new SpeedrunCreeperOnBreakListener(session, settings);
        Creeper creeper = mock(Creeper.class);
        when(world.spawnEntity(any(Location.class), org.mockito.ArgumentMatchers.eq(EntityType.CREEPER)))
                .thenReturn(creeper);

        listener.onBreak(breakEventBy(ALICE));

        verify(world).spawnEntity(any(Location.class), org.mockito.ArgumentMatchers.eq(EntityType.CREEPER));
    }

    @Test
    @DisplayName("does nothing before the run has started")
    void doesNothingBeforeStart() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        SpeedrunCreeperOnBreakListener listener = new SpeedrunCreeperOnBreakListener(session, settings);

        listener.onBreak(breakEventBy(ALICE));

        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test
    @DisplayName("does nothing while the run is paused")
    void doesNothingWhilePaused() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        session.pauseForEmptyRoster();
        SpeedrunCreeperOnBreakListener listener = new SpeedrunCreeperOnBreakListener(session, settings);

        listener.onBreak(breakEventBy(ALICE));

        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test
    @DisplayName("does nothing once the run has finished")
    void doesNothingAfterFinish() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        session.finish("done");
        SpeedrunCreeperOnBreakListener listener = new SpeedrunCreeperOnBreakListener(session, settings);

        listener.onBreak(breakEventBy(ALICE));

        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test
    @DisplayName("does not charge a non-participant for a block they broke")
    void ignoresNonParticipants() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnBreakListener listener = new SpeedrunCreeperOnBreakListener(session, settings);

        listener.onBreak(breakEventBy(BOB));

        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test
    @DisplayName("a 0% spawn chance never spawns a creeper")
    void respectsAZeroSpawnChance() {
        settings.set("creeper-spawn-chance-on-break-percent", "0");
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnBreakListener listener = new SpeedrunCreeperOnBreakListener(session, settings);

        listener.onBreak(breakEventBy(ALICE));

        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test
    @DisplayName("a 100% spawn chance always spawns a creeper")
    void respectsAFullSpawnChance() {
        settings.set("creeper-spawn-chance-on-break-percent", "100");
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnBreakListener listener = new SpeedrunCreeperOnBreakListener(session, settings);
        when(world.spawnEntity(any(Location.class), org.mockito.ArgumentMatchers.eq(EntityType.CREEPER)))
                .thenReturn(mock(Creeper.class));

        listener.onBreak(breakEventBy(ALICE));

        verify(world).spawnEntity(any(Location.class), org.mockito.ArgumentMatchers.eq(EntityType.CREEPER));
    }

    @Test
    @DisplayName("a 100% charged-creeper chance always powers the creeper")
    void alwaysPowersAtFullChance() {
        settings.set("charged-creeper-chance-on-break-percent", "100");
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnBreakListener listener = new SpeedrunCreeperOnBreakListener(session, settings);
        Creeper creeper = mock(Creeper.class);
        when(world.spawnEntity(any(Location.class), org.mockito.ArgumentMatchers.eq(EntityType.CREEPER)))
                .thenReturn(creeper);

        listener.onBreak(breakEventBy(ALICE));

        verify(creeper).setPowered(true);
    }

    @Test
    @DisplayName("a 0% charged-creeper chance never powers the creeper")
    void neverPowersAtZeroChance() {
        settings.set("charged-creeper-chance-on-break-percent", "0");
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnBreakListener listener = new SpeedrunCreeperOnBreakListener(session, settings);
        Creeper creeper = mock(Creeper.class);
        when(world.spawnEntity(any(Location.class), org.mockito.ArgumentMatchers.eq(EntityType.CREEPER)))
                .thenReturn(creeper);

        listener.onBreak(breakEventBy(ALICE));

        verify(creeper, never()).setPowered(true);
    }
}
