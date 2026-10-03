package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.model.Hunt;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("putting caught Runners back")
class EliminationsTest {

    private static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());

    @Test
    @DisplayName("a hunt ended by the plugin shutting down still stands its spectators up")
    void restoreAllWhileShuttingDown() {
        // Paper refuses to schedule for a disabled plugin, and a module is disabled from inside its
        // plugin's onDisable: the scheduled restore threw, and nobody was put back.
        Server server = mock(Server.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("manhunt");
        when(plugin.namespace()).thenReturn("manhunt");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(false);
        Player runner = mock(Player.class);
        when(runner.getUniqueId()).thenReturn(RUNNER);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(data.has(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(true);
        when(runner.getPersistentDataContainer()).thenReturn(data);
        when(runner.getGameMode()).thenReturn(GameMode.SPECTATOR);
        when(runner.getRespawnLocation()).thenReturn(new Location(mock(World.class), 0, 64, 0));
        EntityScheduler refusing = mock(EntityScheduler.class);
        when(refusing.run(any(), any(), any())).thenThrow(
                new IllegalPluginAccessException("Plugin attempted to register task while disabled"));
        when(runner.getScheduler()).thenReturn(refusing);
        when(server.getPlayer(RUNNER)).thenReturn(runner);
        Hunt hunt = Hunt.of(Set.of(RUNNER, UUID.randomUUID()), Set.of(RUNNER));
        hunt.eliminate(RUNNER);

        assertThatCode(() -> new Eliminations(plugin).restoreAll(hunt)).doesNotThrowAnyException();

        verify(runner).setGameMode(GameMode.SURVIVAL);
    }
}
