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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one branching worth its own test: bare releases the caller, a name needs
 * {@link PermissionNodes#LEMMEMOVE_OTHERS}, and a name nobody is online under refuses cleanly.
 */
class SpeedrunLemmemoveCommandTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    @TempDir
    Path dataFolder;

    private SpeedrunLobby lobby;
    private Messages messages;
    private SpeedrunLemmemoveCommand command;
    private CommandSourceStack source;

    @BeforeEach
    void setUp() {
        SettingsStore<SpeedrunSettings> settings = new SettingsStore<>(
                SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                dataFolder.resolve("speedrun.yml"));
        settings.load();
        lobby = new SpeedrunLobby(mock(org.bukkit.plugin.Plugin.class), settings);
        messages = mock(Messages.class);
        command = new SpeedrunLemmemoveCommand(() -> new SpeedrunAdminServices(lobby, messages));
        source = mock(CommandSourceStack.class);
    }

    private static Player playerWithId(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        return player;
    }

    @Test
    @DisplayName("bare, releases whoever ran it")
    void bareReleasesTheCaller() {
        Player alice = playerWithId(ALICE, "Alice");
        when(source.getSender()).thenReturn(alice);

        command.execute(source, new String[0]);

        assertReleased(ALICE);
        verify(messages).send(alice, "speedrun.lemmemove.done", "player", "Alice");
    }

    @Test
    @DisplayName("console with no name is refused, not a NullPointerException")
    void consoleWithNoNameIsRefused() {
        CommandSender console = mock(CommandSender.class);
        when(source.getSender()).thenReturn(console);

        command.execute(source, new String[0]);

        assertNotReleased(ALICE);
        verify(messages).send(console, "speedrun.lemmemove.console-needs-a-player");
    }

    @Test
    @DisplayName("naming somebody else without the permission is refused")
    void namingSomebodyElseWithoutPermissionIsRefused() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)).thenReturn(false);
        when(source.getSender()).thenReturn(sender);

        command.execute(source, new String[] {"Bob"});

        assertNotReleased(ALICE);
        verify(messages).send(sender, "speedrun.lemmemove.no-permission-for-others");
    }

    @Test
    @DisplayName("naming somebody else with the permission releases them instead")
    void namingSomebodyElseWithPermissionReleasesThem() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Player bob = playerWithId(UUID.nameUUIDFromBytes("bob".getBytes()), "Bob");
            BukkitServerStub.online(bukkit, "Bob", bob);
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)).thenReturn(true);
            when(source.getSender()).thenReturn(sender);

            command.execute(source, new String[] {"Bob"});

            assertReleased(bob.getUniqueId());
            verify(messages).send(sender, "speedrun.lemmemove.done", "player", "Bob");
        }
    }

    @Test
    @DisplayName("naming somebody not online is refused cleanly")
    void namingSomebodyOfflineIsRefused() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            BukkitServerStub.online(bukkit, "Ghost", null);
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)).thenReturn(true);
            when(source.getSender()).thenReturn(sender);

            command.execute(source, new String[] {"Ghost"});

            verify(messages).send(sender, "speedrun.lemmemove.player-not-found", "player", "Ghost");
        }
    }

    @Test
    @DisplayName("a nickname names somebody too, and is offered while typing")
    void nicknamesCount(@TempDir Path nicknameDir) {
        de.raindancer.core.data.sql.Database database = de.raindancer.core.data.sql.Database.open(
                nicknameDir.resolve("core.db"), de.raindancer.core.data.sql.CoreSchema.CORE, () -> false);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            de.raindancer.core.ui.identity.Nicknames nicknames = new de.raindancer.core.ui.identity.Nicknames(database);
            de.raindancer.core.platform.command.PlayerTargets.useNicknames(nicknames);
            Player bob = playerWithId(UUID.nameUUIDFromBytes("bob".getBytes()), "Bob");
            BukkitServerStub.online(bukkit, "Bob", bob);
            org.bukkit.Server server = Bukkit.getServer();
            when(server.getPlayer(bob.getUniqueId())).thenReturn(bob);
            org.mockito.Mockito.doReturn(java.util.List.of(bob)).when(server).getOnlinePlayers();
            nicknames.remember(bob.getUniqueId(), "Big Bob");
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)).thenReturn(true);
            when(source.getSender()).thenReturn(sender);

            command.execute(source, new String[] {"Big_Bob"});

            assertReleased(bob.getUniqueId());
            org.assertj.core.api.Assertions.assertThat(command.suggest(source, new String[] {"Big_"}))
                    .containsExactly("Big_Bob");
        } finally {
            de.raindancer.core.platform.command.PlayerTargets.useNicknames(null);
            database.close();
        }
    }

    private void assertReleased(UUID id) {
        org.assertj.core.api.Assertions.assertThat(lobby.isReleased(id)).isTrue();
    }

    private void assertNotReleased(UUID id) {
        org.assertj.core.api.Assertions.assertThat(lobby.isReleased(id)).isFalse();
    }

    @Test
    @DisplayName("a selector releases everybody it matches")
    void selectorReleasesTheGroup() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Player bob = playerWithId(UUID.nameUUIDFromBytes("bob".getBytes()), "Bob");
            Player cy = playerWithId(UUID.nameUUIDFromBytes("cy".getBytes()), "Cy");
            when(bob.isOnline()).thenReturn(true);
            when(cy.isOnline()).thenReturn(true);
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)).thenReturn(true);
            when(source.getSender()).thenReturn(sender);
            BukkitServerStub.selector(bukkit, sender, "@a", bob, cy);

            command.execute(source, new String[] {"@a"});

            assertReleased(bob.getUniqueId());
            assertReleased(cy.getUniqueId());
        }
    }

    @Test
    @DisplayName("a selector without the vanilla permission is refused with its own words")
    void selectorRefused() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            BukkitServerStub.online(bukkit, "unused", null);
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)).thenReturn(true);
            when(source.getSender()).thenReturn(sender);

            command.execute(source, new String[] {"@a"});

            verify(messages).send(sender, "speedrun.command.selector-refused", "player", "@a");
        }
    }

    @Test
    @DisplayName("somebody known but offline is called offline, not unknown")
    void offlineIsSaid() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            BukkitServerStub.offline(bukkit, "Ghost", UUID.nameUUIDFromBytes("ghost".getBytes()));
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)).thenReturn(true);
            when(source.getSender()).thenReturn(sender);

            command.execute(source, new String[] {"Ghost"});

            verify(messages).send(sender, "speedrun.command.player-offline", "player", "Ghost");
        }
    }
}
