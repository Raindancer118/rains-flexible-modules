package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.model.ManhuntTeams;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Three things a real hunt with two real clients showed, that no test had.
 *
 * <p>Found by joining two bot players to a local Paper 26.2 server and running a hunt end to end:
 * the Hunter did get the tracking compass — but both players were already carrying speedrun-module's
 * lobby compass and start block, the circle teleport at "go" was announced as a Runner reaching the
 * Overworld, and every Hunter was sent the same compass target twice a second.
 */
class LiveHuntFindingsTest {

    // ------------------------------------------------------------------ the speedrun lobby's items

    private static ItemStack withKeys(NamespacedKey... keys) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.getKeys()).thenReturn(Set.of(keys));
        return stack;
    }

    @Test
    @DisplayName("the speedrun lobby's compass and start block are taken off a participant as the hunt begins")
    void speedrunLobbyItemsAreTakenAway() {
        ItemStack lobbyCompass = withKeys(new NamespacedKey("rainsspeedrun", "speedrun-lobby-item"));
        ItemStack sword = withKeys();
        ItemStack startBlock = withKeys(new NamespacedKey("rainsspeedrun", "speedrun-lobby-item"));
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[]{lobbyCompass, sword, null, startBlock});
        Player player = mock(Player.class);
        when(player.getInventory()).thenReturn(inventory);

        ManhuntService.dropSpeedrunLobbyItems(player);

        verify(inventory).setItem(0, null);
        verify(inventory).setItem(3, null);
        verify(inventory, never()).setItem(1, null);
    }

    @Test
    @DisplayName("found by the key's name, whatever plugin namespace speedrun-module is running under")
    void anyNamespaceCounts() {
        assertThat(ManhuntService.isSpeedrunLobbyItem(withKeys(new NamespacedKey("yeuksmp", "speedrun-lobby-item"))))
                .isTrue();
        assertThat(ManhuntService.isSpeedrunLobbyItem(withKeys(new NamespacedKey("manhunt", "manhunt-tracker"))))
                .isFalse();
        assertThat(ManhuntService.isSpeedrunLobbyItem(null)).isFalse();
    }

    // ------------------------------------------------------------------ "reached the Overworld" at go

    @Test
    @DisplayName("moving between two worlds of the same kind is not a Runner changing dimension")
    void sameEnvironmentIsNotNarrated() {
        UUID runner = UUID.randomUUID();
        ManhuntTeams teams = new ManhuntTeams(() -> false);
        teams.joinRunners(runner);
        ManhuntService manhunt = mock(ManhuntService.class);
        when(manhunt.isRunning()).thenReturn(true);
        when(manhunt.teams()).thenReturn(teams);
        ManhuntNarrator narrator = mock(ManhuntNarrator.class);
        ManhuntNarrationListener listener = new ManhuntNarrationListener(manhunt, mock(ManhuntLives.class), narrator);

        World lobby = mock(World.class);
        World huntWorld = mock(World.class);
        when(lobby.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(huntWorld.getEnvironment()).thenReturn(World.Environment.NORMAL);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(runner);
        when(player.getWorld()).thenReturn(huntWorld);

        listener.onWorldChange(new PlayerChangedWorldEvent(player, lobby));

        verify(narrator, never()).runnerChangedWorld(anyString(), anyString());
    }

    // ------------------------------------------------------------------ the same compass target, twice a second

    @Test
    @DisplayName("a compass target is only sent when it actually moved")
    void compassTargetOnlyWhenItMoved() {
        CompassTargets targets = new CompassTargets();
        UUID hunter = UUID.randomUUID();

        assertThat(targets.moved(hunter, "world", 10, 64, 20)).as("first time").isTrue();
        assertThat(targets.moved(hunter, "world", 10, 64, 20)).as("same block").isFalse();
        assertThat(targets.moved(hunter, "world", 11, 64, 20)).as("a block further").isTrue();
        assertThat(targets.moved(hunter, "world_nether", 11, 64, 20)).as("another world").isTrue();

        targets.forget(hunter);
        assertThat(targets.moved(hunter, "world_nether", 11, 64, 20)).as("after forgetting").isTrue();
    }
}
