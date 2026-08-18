package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.messages.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The one way into the lobby world at all — see the class javadoc for why this exists. */
class SpeedrunJoinCommandTest {

    @TempDir
    Path dataFolder;

    private SpeedrunLobby lobby;
    private Messages messages;
    private SpeedrunJoinCommand command;
    private CommandSourceStack source;

    @BeforeEach
    void setUp() {
        SettingsStore<SpeedrunSettings> settings = new SettingsStore<>(
                SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                dataFolder.resolve("speedrun.yml"));
        settings.load();
        lobby = new SpeedrunLobby(mock(org.bukkit.plugin.Plugin.class), settings);
        messages = mock(Messages.class);
        command = new SpeedrunJoinCommand(() -> new SpeedrunAdminServices(lobby, messages));
        source = mock(CommandSourceStack.class);
    }

    @Test
    @DisplayName("teleports the sender to the lobby world's spawn")
    void teleportsToTheLobbySpawn() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            World lobbyWorld = mock(World.class);
            Location spawn = new Location(lobbyWorld, 0, 70, 0);
            when(lobbyWorld.getSpawnLocation()).thenReturn(spawn);
            bukkit.when(() -> Bukkit.getWorld(SpeedrunSettings.DEFAULT_WORLD_NAME)).thenReturn(lobbyWorld);
            Player player = mock(Player.class);
            when(source.getSender()).thenReturn(player);

            command.execute(source, new String[0]);

            verify(player).teleportAsync(spawn);
        }
    }

    @Test
    @DisplayName("a non-player sender is refused, not thrown at")
    void nonPlayerSenderRefused() {
        CommandSender console = mock(CommandSender.class);
        when(source.getSender()).thenReturn(console);

        command.execute(source, new String[0]);

        verify(messages).send(console, "speedrun.join.only-a-player");
    }

    @Test
    @DisplayName("refuses cleanly when the lobby world is not loaded")
    void refusesWhenWorldMissing() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(SpeedrunSettings.DEFAULT_WORLD_NAME)).thenReturn(null);
            Player player = mock(Player.class);
            when(source.getSender()).thenReturn(player);

            command.execute(source, new String[0]);

            verify(messages).send(player, "speedrun.join.world-missing", "world",
                    SpeedrunSettings.DEFAULT_WORLD_NAME);
            verify(player, never()).teleportAsync(any(Location.class));
        }
    }
}
