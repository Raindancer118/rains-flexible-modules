package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirrors {@link SpeedrunCreeperOnBreakListenerTest}'s idiom, one trigger over: a container open
 * instead of a block break, with its own toggle and its own charged-creeper chance.
 */
class SpeedrunCreeperOnContainerOpenListenerTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @TempDir
    Path dataFolder;

    private SettingsStore<SpeedrunSettings> settings;
    private World world;
    private Container container;

    @BeforeEach
    void setUp() {
        settings = new SettingsStore<>(
                SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                dataFolder.resolve("speedrun.yml"));
        settings.load();

        world = mock(World.class);
        container = mock(Container.class);
        when(container.getWorld()).thenReturn(world);
        when(container.getLocation()).thenReturn(new Location(world, 10, 64, 10));
    }

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private InventoryOpenEvent openEventBy(UUID opener, InventoryHolder holder) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(holder);
        Player player = playerWithId(opener);
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(player);
        when(view.getTopInventory()).thenReturn(inventory);
        return new InventoryOpenEvent(view);
    }

    @Test
    @DisplayName("spawns a creeper where a participant opens a container during the run")
    void spawnsCreeperOnParticipantOpen() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
        session.start();
        SpeedrunCreeperOnContainerOpenListener listener =
                new SpeedrunCreeperOnContainerOpenListener(session, settings);
        Creeper creeper = mock(Creeper.class);
        when(world.spawnEntity(any(Location.class), eq(EntityType.CREEPER))).thenReturn(creeper);

        listener.onOpen(openEventBy(ALICE, container));

        verify(world).spawnEntity(any(Location.class), eq(EntityType.CREEPER));
    }

    @Test
    @DisplayName("does nothing for a holder that is not a block container, e.g. a crafting table")
    void ignoresNonContainerHolders() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnContainerOpenListener listener =
                new SpeedrunCreeperOnContainerOpenListener(session, settings);
        // Nothing this listener recognises as a placed block — a crafting table's holder, say.
        InventoryHolder notAContainer = mock(InventoryHolder.class);

        listener.onOpen(openEventBy(ALICE, notAContainer));

        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test
    @DisplayName("spawns at a double chest's own location")
    void handlesDoubleChests() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnContainerOpenListener listener =
                new SpeedrunCreeperOnContainerOpenListener(session, settings);
        DoubleChest doubleChest = mock(DoubleChest.class);
        when(doubleChest.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        when(doubleChest.getWorld()).thenReturn(world);
        Creeper creeper = mock(Creeper.class);
        when(world.spawnEntity(any(Location.class), eq(EntityType.CREEPER))).thenReturn(creeper);

        listener.onOpen(openEventBy(ALICE, doubleChest));

        verify(world).spawnEntity(any(Location.class), eq(EntityType.CREEPER));
    }

    @Test
    @DisplayName("does nothing before the run has started")
    void doesNothingBeforeStart() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        SpeedrunCreeperOnContainerOpenListener listener =
                new SpeedrunCreeperOnContainerOpenListener(session, settings);

        listener.onOpen(openEventBy(ALICE, container));

        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test
    @DisplayName("does not charge a non-participant for opening a container")
    void ignoresNonParticipants() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnContainerOpenListener listener =
                new SpeedrunCreeperOnContainerOpenListener(session, settings);

        listener.onOpen(openEventBy(BOB, container));

        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test
    @DisplayName("a 0% spawn chance never spawns a creeper, separately from the block-break one")
    void respectsAZeroSpawnChance() {
        settings.set("creeper-spawn-chance-on-container-percent", "0");
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnContainerOpenListener listener =
                new SpeedrunCreeperOnContainerOpenListener(session, settings);

        listener.onOpen(openEventBy(ALICE, container));

        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test
    @DisplayName("a 100% spawn chance always spawns a creeper")
    void respectsAFullSpawnChance() {
        settings.set("creeper-spawn-chance-on-container-percent", "100");
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnContainerOpenListener listener =
                new SpeedrunCreeperOnContainerOpenListener(session, settings);
        when(world.spawnEntity(any(Location.class), eq(EntityType.CREEPER))).thenReturn(mock(Creeper.class));

        listener.onOpen(openEventBy(ALICE, container));

        verify(world).spawnEntity(any(Location.class), eq(EntityType.CREEPER));
    }

    @Test
    @DisplayName("a 100% charged-creeper chance on containers always powers the creeper")
    void alwaysPowersAtFullChance() {
        settings.set("charged-creeper-chance-on-container-percent", "100");
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnContainerOpenListener listener =
                new SpeedrunCreeperOnContainerOpenListener(session, settings);
        Creeper creeper = mock(Creeper.class);
        when(world.spawnEntity(any(Location.class), eq(EntityType.CREEPER))).thenReturn(creeper);

        listener.onOpen(openEventBy(ALICE, container));

        verify(creeper).setPowered(true);
    }

    @Test
    @DisplayName("a 0% charged-creeper chance on containers never powers the creeper")
    void neverPowersAtZeroChance() {
        settings.set("charged-creeper-chance-on-container-percent", "0");
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunCreeperOnContainerOpenListener listener =
                new SpeedrunCreeperOnContainerOpenListener(session, settings);
        Creeper creeper = mock(Creeper.class);
        when(world.spawnEntity(any(Location.class), eq(EntityType.CREEPER))).thenReturn(creeper);

        listener.onOpen(openEventBy(ALICE, container));

        verify(creeper, never()).setPowered(true);
    }
}
