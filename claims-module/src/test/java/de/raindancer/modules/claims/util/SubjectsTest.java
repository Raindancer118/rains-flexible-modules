package de.raindancer.modules.claims.util;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubjectsTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final Player admin = mock(Player.class);
    private final Player lilly = mock(Player.class);
    private final OfflinePlayer sleepy = mock(OfflinePlayer.class);
    private final OfflinePlayer thief = mock(OfflinePlayer.class);
    private Database database;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        Nicknames nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        when(lilly.getName()).thenReturn("lillyyxoxo");
        when(lilly.isOnline()).thenReturn(true);
        when(lilly.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("lillyyxoxo".getBytes()));
        when(server.getPlayerExact("lillyyxoxo")).thenReturn(lilly);
        when(sleepy.getName()).thenReturn("Sleepy");
        when(sleepy.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("Sleepy".getBytes()));
        when(server.getOfflinePlayerIfCached("Sleepy")).thenReturn(sleepy);
        when(server.getOfflinePlayer(sleepy.getUniqueId())).thenReturn(sleepy);
        nicknames.remember(sleepy.getUniqueId(), "Sleepy Head");
        // somebody who took "Sleepy" as a nickname must not be able to stand in for the real Sleepy
        when(thief.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("thief".getBytes()));
        nicknames.remember(thief.getUniqueId(), "Sleepyy");
    }

    @AfterEach
    void tearDown() {
        PlayerTargets.useNicknames(null);
        database.close();
    }

    @Test
    @DisplayName("somebody offline resolves by name and by nickname, to their own uuid")
    void offline() {
        assertThat(Subjects.one(server, messages, admin, "Sleepy")).contains(sleepy.getUniqueId());
        assertThat(Subjects.one(server, messages, admin, "Sleepy_Head")).contains(sleepy.getUniqueId());
    }

    @Test
    @DisplayName("a real name that is also somebody's nickname still means the real player")
    void realNameFirst() {
        Nicknames nicknames = new Nicknames(database);
        nicknames.remember(thief.getUniqueId(), "Sleepy");

        assertThat(Subjects.one(server, messages, admin, "Sleepy")).contains(sleepy.getUniqueId());
    }

    @Test
    @DisplayName("a selector counts when it matches one, is refused without the node, and does not guess among several")
    void selectors() {
        doReturn(List.<Entity>of(lilly)).when(server).selectEntities(admin, "@p");
        doReturn(List.<Entity>of(lilly, admin)).when(server).selectEntities(admin, "@a");

        assertThat(Subjects.one(server, messages, admin, "@p")).isEmpty();
        verify(messages).send(admin, "error.selector-refused", "selector", "@p");

        when(admin.hasPermission("minecraft.command.selector")).thenReturn(true);
        assertThat(Subjects.one(server, messages, admin, "@p")).contains(lilly.getUniqueId());
        assertThat(Subjects.one(server, messages, admin, "@a")).isEmpty();
        verify(messages).send(admin, "error.too-many-players", "selector", "@a", "count", "2");
    }

    @Test
    @DisplayName("something that must be online says the player is offline, not unknown")
    void onlineNeeded() {
        assertThat(Subjects.online(server, messages, admin, "Sleepy")).isEmpty();
        verify(messages).send(admin, "error.player-offline", "player", "Sleepy Head");

        assertThat(Subjects.online(server, messages, admin, "lillyyxoxo")).contains(lilly);
        assertThat(Subjects.online(server, messages, admin, "Nobody")).isEmpty();
        verify(messages).send(admin, "error.no-such-player", "player", "Nobody");
    }

    @Test
    @DisplayName("completion offers the offline, and a hidden online player exactly as if they were offline")
    void completion() {
        doReturn(List.of(lilly)).when(server).getOnlinePlayers();
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[]{sleepy, lilly});

        List<String> hidden = Subjects.suggest(server, admin, "", who -> false);
        assertThat(hidden).contains("Sleepy", "lillyyxoxo");
        assertThat(hidden.indexOf("lillyyxoxo")).as("offered among the offline, not as online")
                .isGreaterThan(hidden.indexOf("@s"));
        assertThat(Subjects.suggest(server, admin, "", who -> true)).contains("Sleepy", "lillyyxoxo");
    }
}
