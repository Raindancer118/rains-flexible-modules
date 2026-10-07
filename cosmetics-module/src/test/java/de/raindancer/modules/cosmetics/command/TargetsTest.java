package de.raindancer.modules.cosmetics.command;

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

class TargetsTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final Player admin = mock(Player.class);
    private final Player lilly = mock(Player.class);
    private final OfflinePlayer sleepy = mock(OfflinePlayer.class);
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
        nicknames.remember(sleepy.getUniqueId(), "Sleepy Head");
        when(server.getOfflinePlayer(sleepy.getUniqueId())).thenReturn(sleepy);
    }

    @AfterEach
    void tearDown() {
        PlayerTargets.useNicknames(null);
        database.close();
    }

    @Test
    @DisplayName("somebody offline is found by name and by nickname, for things kept by id")
    void offline() {
        assertThat(Targets.anybody(server, messages, admin, "Sleepy")).containsExactly(sleepy);
        assertThat(Targets.anybody(server, messages, admin, "Sleepy_Head")).containsExactly(sleepy);
    }

    @Test
    @DisplayName("@a is every player it matches, once the sender may use selectors")
    void selector() {
        when(admin.hasPermission("minecraft.command.selector")).thenReturn(true);
        doReturn(List.<Entity>of(lilly)).when(server).selectEntities(admin, "@a");

        assertThat(Targets.anybody(server, messages, admin, "@a")).containsExactly(lilly);
    }

    @Test
    @DisplayName("a refused selector and a name nobody has are different answers")
    void refusals() {
        assertThat(Targets.anybody(server, messages, admin, "@a")).isEmpty();
        verify(messages).send(admin, "cosmetics.selector-refused", "selector", "@a");

        assertThat(Targets.anybody(server, messages, admin, "Nope")).isEmpty();
        verify(messages).send(admin, "cosmetics.unknown-player", "player", "Nope");
    }
}
