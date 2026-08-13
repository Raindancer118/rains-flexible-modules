package de.raindancer.modules.speedrun;

import de.raindancer.core.moderation.players.PlayerAdmin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Putting racers and the map back to a standard starting point — see {@link SpeedrunPreparation}'s
 * own class javadoc for why every run gets this, not only ones that follow a regeneration.
 */
class SpeedrunPreparationTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @Test
    @DisplayName("heals, feeds, cures and extinguishes every participant")
    void resetsEveryParticipant() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(players);
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(null);
            bukkit.when(() -> Bukkit.getPlayer(BOB)).thenReturn(null);

            preparation.prepare(world, Set.of(ALICE, BOB));
        }

        verify(players).heal(ALICE);
        verify(players).feed(ALICE);
        verify(players).cure(ALICE);
        verify(players).extinguish(ALICE);
        verify(players).heal(BOB);
        verify(players).feed(BOB);
        verify(players).cure(BOB);
        verify(players).extinguish(BOB);
    }

    @Test
    @DisplayName("sets a participant's saturation to full when they are online")
    void fillsSaturationForOnlineParticipants() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(players);
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());
        Player onlineAlice = mock(Player.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(onlineAlice);

            preparation.prepare(world, Set.of(ALICE));
        }

        verify(onlineAlice).setSaturation(20f);
    }

    @Test
    @DisplayName("sets the world to morning")
    void setsTheWorldToMorning() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(players);
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            preparation.prepare(world, Set.of());
        }

        verify(world).setTime(SpeedrunPreparation.DAY_START);
    }

    @Test
    @DisplayName("removes every hostile mob and every dropped item, and nothing else")
    void clearsHostilesAndItemsOnly() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(players);
        World world = mock(World.class);
        Entity zombie = mock(Zombie.class);
        Entity droppedItem = mock(Item.class);
        Entity innocentCow = mock(Entity.class);
        when(world.getEntities()).thenReturn(List.of(zombie, droppedItem, innocentCow));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            preparation.prepare(world, Set.of());
        }

        verify(zombie).remove();
        verify(droppedItem).remove();
        verify(innocentCow, never()).remove();
    }

    @Test
    @DisplayName("a null world skips the world half without throwing")
    void nullWorldSkipsWorldReset() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(players);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(null);

            preparation.prepare(null, Set.of(ALICE));
        }

        verify(players).heal(ALICE);
    }
}
