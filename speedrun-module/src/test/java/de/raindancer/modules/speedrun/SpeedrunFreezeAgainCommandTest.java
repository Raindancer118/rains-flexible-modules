package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Taking a {@code /lemmemove} back: the same branching as {@link SpeedrunLemmemoveCommandTest}, plus
 * the one thing only this command has — being told it had nothing to undo.
 */
class SpeedrunFreezeAgainCommandTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    @TempDir
    Path dataFolder;

    private SpeedrunLobby lobby;
    private Messages messages;
    private SpeedrunFreezeAgainCommand command;
    private CommandSourceStack source;

    @BeforeEach
    void setUp() {
        SettingsStore<SpeedrunSettings> settings = new SettingsStore<>(
                SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                dataFolder.resolve("speedrun.yml"));
        settings.load();
        lobby = new SpeedrunLobby(mock(org.bukkit.plugin.Plugin.class), settings);
        messages = mock(Messages.class);
        command = new SpeedrunFreezeAgainCommand(() -> new SpeedrunAdminServices(lobby, messages));
        source = mock(CommandSourceStack.class);
    }

    private static Player playerWithId(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        return player;
    }

    @Test
    @DisplayName("bare, freezes whoever ran it again")
    void bareFreezesTheCallerAgain() {
        Player alice = playerWithId(ALICE, "Alice");
        when(source.getSender()).thenReturn(alice);
        lobby.release(ALICE);

        command.execute(source, new String[0]);

        assertThat(lobby.isReleased(ALICE)).isFalse();
        verify(messages).send(alice, "speedrun.freezeagain.done", "player", "Alice");
    }

    @Test
    @DisplayName("somebody who was never released is told so, rather than a done that did nothing")
    void nothingToUndoIsSaidOutLoud() {
        Player alice = playerWithId(ALICE, "Alice");
        when(source.getSender()).thenReturn(alice);

        command.execute(source, new String[0]);

        assertThat(lobby.isReleased(ALICE)).isFalse();
        verify(messages).send(alice, "speedrun.freezeagain.not-released", "player", "Alice");
    }

    @Test
    @DisplayName("console with no name is refused, not a NullPointerException")
    void consoleWithNoNameIsRefused() {
        CommandSender console = mock(CommandSender.class);
        when(source.getSender()).thenReturn(console);

        command.execute(source, new String[0]);

        verify(messages).send(console, "speedrun.freezeagain.console-needs-a-player");
    }

    @Test
    @DisplayName("naming somebody else without the permission is refused")
    void namingSomebodyElseWithoutPermissionIsRefused() {
        UUID bob = UUID.nameUUIDFromBytes("bob".getBytes());
        lobby.release(bob);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)).thenReturn(false);
        when(source.getSender()).thenReturn(sender);

        command.execute(source, new String[] {"Bob"});

        assertThat(lobby.isReleased(bob)).as("still released — nothing was undone").isTrue();
        verify(messages).send(sender, "speedrun.freezeagain.no-permission-for-others");
    }

    @Test
    @DisplayName("naming somebody else with the permission freezes them instead")
    void namingSomebodyElseWithPermissionFreezesThem() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Player bob = playerWithId(UUID.nameUUIDFromBytes("bob".getBytes()), "Bob");
            bukkit.when(() -> Bukkit.getPlayerExact("Bob")).thenReturn(bob);
            lobby.release(bob.getUniqueId());
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)).thenReturn(true);
            when(source.getSender()).thenReturn(sender);

            command.execute(source, new String[] {"Bob"});

            assertThat(lobby.isReleased(bob.getUniqueId())).isFalse();
            verify(messages).send(sender, "speedrun.freezeagain.done", "player", "Bob");
        }
    }

    @Test
    @DisplayName("naming somebody not online is refused cleanly")
    void namingSomebodyOfflineIsRefused() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Ghost")).thenReturn(null);
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)).thenReturn(true);
            when(source.getSender()).thenReturn(sender);

            command.execute(source, new String[] {"Ghost"});

            verify(messages).send(sender, "speedrun.freezeagain.player-not-found", "player", "Ghost");
        }
    }
}
