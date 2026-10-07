package de.raindancer.modules.moderation;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.util.Players;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayersSelectorsTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final Messages messages = mock(Messages.class);
    private Database database;
    private Player lilly;
    private Player sam;
    private OfflinePlayer ghost;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        Nicknames nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        lilly = online("lillyyxoxo");
        sam = online("Sam");
        doReturn(List.of(lilly, sam)).when(server).getOnlinePlayers();
        ghost = mock(OfflinePlayer.class);
        UUID ghostId = UUID.nameUUIDFromBytes("ghost".getBytes());
        when(ghost.getUniqueId()).thenReturn(ghostId);
        when(ghost.getName()).thenReturn("OldGhost");
        when(server.getOfflinePlayer(ghostId)).thenReturn(ghost);
        when(server.getOfflinePlayerIfCached("OldGhost")).thenReturn(ghost);
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[]{ghost});
        nicknames.remember(lilly.getUniqueId(), "Lilly Pad");
        nicknames.remember(ghostId, "Spooky");
        when(sender.hasPermission(PlayerTargets.SELECTOR_NODE)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        PlayerTargets.useNicknames(null);
        database.close();
    }

    private Player online(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(server.getPlayerExact(name)).thenReturn(player);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    @Test
    @DisplayName("a selector that matches exactly one player is that player")
    void selectorOne() {
        doReturn(List.<Entity>of(sam)).when(server).selectEntities(any(), anyString());

        assertThat(Players.one(messages, server, sender, "@p")).contains(sam);
    }

    @Test
    @DisplayName("/ban @a is refused, not guessed at")
    void selectorMany() {
        doReturn(List.<Entity>of(lilly, sam)).when(server).selectEntities(any(), anyString());

        assertThat(Players.one(messages, server, sender, "@a")).isEmpty();

        verify(messages).send(sender, "moderation.player.ambiguous", "selector", "@a", "count", "2");
    }

    @Test
    @DisplayName("a sender without the selector permission is told, and no selector is evaluated")
    void selectorRefused() {
        when(sender.hasPermission(PlayerTargets.SELECTOR_NODE)).thenReturn(false);

        assertThat(Players.one(messages, server, sender, "@a")).isEmpty();

        verify(messages).send(sender, "moderation.player.selector-refused", "selector", "@a");
        verify(server, never()).selectEntities(any(), anyString());
    }

    @Test
    @DisplayName("offline players resolve by real name and by nickname, with the real identity")
    void offlineByNameAndNickname() {
        assertThat(Players.one(messages, server, sender, "OldGhost")).contains(ghost);
        assertThat(Players.one(messages, server, sender, "Spooky")).contains(ghost);
    }

    @Test
    @DisplayName("a typo is 'nobody', and a real name is never redirected by somebody's nickname")
    void typoAndRealNameFirst() {
        assertThat(Players.one(messages, server, sender, "Nope")).isEmpty();
        verify(messages).send(sender, "moderation.no-such-player", "player", "Nope");

        Nicknames hostile = new Nicknames(database);
        PlayerTargets.useNicknames(hostile);
        hostile.remember(sam.getUniqueId(), "OldGhost");
        assertThat(Players.one(messages, server, sender, "OldGhost")).contains(ghost);
    }

    @Test
    @DisplayName("tab completion: selectors only for who may use them, offline always, the vanished as offline")
    void suggestions() {
        assertThat(Players.suggest(server, sender, "")).contains("@a", "Sam", "OldGhost", "Spooky");
        when(sender.hasPermission(PlayerTargets.SELECTOR_NODE)).thenReturn(false);
        assertThat(Players.suggest(server, sender, "")).doesNotContain("@a").contains("OldGhost", "Spooky");

        Vanish vanish = mock(Vanish.class);
        Player viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(viewer.hasPermission(PlayerTargets.SELECTOR_NODE)).thenReturn(true);
        when(vanish.canSee(any(), any())).thenReturn(true);
        when(vanish.canSee(viewer.getUniqueId(), lilly.getUniqueId())).thenReturn(false);
        assertThat(Players.suggest(server, viewer, "Lilly", vanish)).contains("Lilly_Pad");
        assertThat(Players.suggest(server, viewer, "Spooky", vanish)).contains("Spooky");
    }
}
