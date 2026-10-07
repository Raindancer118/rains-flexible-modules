package de.raindancer.modules.essentials.util;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.core.ui.messages.Messages;
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

class PlayersLookupTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final Messages messages = mock(Messages.class);
    private Database database;
    private Nicknames nicknames;
    private Player lilly;
    private Player sam;
    private OfflinePlayer ghost;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        nicknames = new Nicknames(database);
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
        doReturn(List.<Entity>of(lilly, sam)).when(server).selectEntities(any(), anyString());
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
    @DisplayName("a selector matching several is acted on by every one, or refused when one is wanted")
    void selectorMany() {
        assertThat(Players.online(messages, server, sender, "@a", false, "k")).containsExactly(lilly, sam);

        assertThat(Players.online(messages, server, sender, "@a", true, "k")).isEmpty();
        verify(messages).send(sender, "essentials.player.ambiguous", "selector", "@a", "count", "2");
    }

    @Test
    @DisplayName("somebody who may not use selectors is told so, and nothing is resolved")
    void selectorRefused() {
        when(sender.hasPermission(PlayerTargets.SELECTOR_NODE)).thenReturn(false);

        assertThat(Players.online(messages, server, sender, "@a", false, "k")).isEmpty();

        verify(messages).send(sender, "essentials.player.selector-refused", "selector", "@a");
        verify(server, never()).selectEntities(any(), anyString());
    }

    @Test
    @DisplayName("an offline name, or an offline nickname, is 'offline' and not 'nobody'")
    void offline() {
        assertThat(Players.online(messages, server, sender, "OldGhost", true, "k")).isEmpty();
        assertThat(Players.online(messages, server, sender, "Spooky", true, "k")).isEmpty();

        verify(messages).send(sender, "essentials.player.offline", "player", "OldGhost");
        verify(messages).send(sender, "essentials.player.offline", "player", "Spooky");
        verify(messages, never()).send(any(CommandSender.class), org.mockito.ArgumentMatchers.eq("k"), any(Object[].class));
    }

    @Test
    @DisplayName("nobody at all gets the command's own wording")
    void nobody() {
        assertThat(Players.online(messages, server, sender, "Nope", true, "k")).isEmpty();

        verify(messages).send(sender, "k", "player", "Nope");
    }

    @Test
    @DisplayName("offline players can be found by name and by nickname for UUID-based commands")
    void oneOffline() {
        assertThat(Players.one(messages, server, sender, "Spooky")).contains(ghost);
        assertThat(Players.one(messages, server, sender, "OldGhost")).contains(ghost);
        assertThat(Players.one(messages, server, sender, "Lilly_Pad")).contains(lilly);
    }

    @Test
    @DisplayName("a real name wins over somebody else's nickname that reads the same")
    void realNameWins() {
        nicknames.remember(sam.getUniqueId(), "OldGhost");

        assertThat(Players.one(messages, server, sender, "OldGhost")).contains(ghost);
    }

    @Test
    @DisplayName("tab completion offers selectors only to who may use them, and offline names always")
    void suggestions() {
        Vanish vanish = mock(Vanish.class);
        when(vanish.canSee(any(), any())).thenReturn(true);

        assertThat(Players.suggest(server, sender, "", vanish)).contains("@a", "Sam", "Spooky", "OldGhost");

        when(sender.hasPermission(PlayerTargets.SELECTOR_NODE)).thenReturn(false);
        assertThat(Players.suggest(server, sender, "", vanish)).doesNotContain("@a").contains("OldGhost", "Spooky");
    }

    @Test
    @DisplayName("a vanished player's nickname is completed as an offline player's is — never as online, never missing")
    void vanishedNicknameLooksOffline() {
        Vanish vanish = mock(Vanish.class);
        when(vanish.canSee(any(), any())).thenReturn(true);
        Player viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(viewer.hasPermission(PlayerTargets.SELECTOR_NODE)).thenReturn(true);
        when(vanish.canSee(viewer.getUniqueId(), lilly.getUniqueId())).thenReturn(false);

        List<String> offered = Players.suggest(server, viewer, "", vanish);
        assertThat(offered).contains("Lilly_Pad");
        assertThat(offered.indexOf("Lilly_Pad")).as("among the offline, after the visible online")
                .isGreaterThan(offered.indexOf("@s"));
        assertThat(Players.suggest(server, viewer, "Spooky", vanish)).contains("Spooky");
    }
}
