package de.raindancer.modules.moderation;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.modules.moderation.util.Players;
import org.bukkit.Server;
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
import static org.mockito.Mockito.when;

class PlayersNicknameTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private Database database;
    private Player lilly;
    private Player ghost;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        Nicknames nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        lilly = online("lillyyxoxo");
        ghost = online("Ghost");
        doReturn(List.of(lilly, ghost)).when(server).getOnlinePlayers();
        nicknames.remember(lilly.getUniqueId(), "Lilly Pad");
        nicknames.remember(ghost.getUniqueId(), "Boo");
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
        when(server.getPlayerExact(name)).thenReturn(player);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    @Test
    @DisplayName("a nickname finds its player, and /ban Lilly_Pad bans the right person")
    void findsByNickname() {
        assertThat(Players.find(server, "Lilly_Pad")).contains(lilly);
        assertThat(Players.idOf(server, "lilly_pad")).contains(lilly.getUniqueId());
        assertThat(Players.find(server, "nobody_at_all")).isEmpty();
    }

    @Test
    @DisplayName("tab completion offers nicknames next to names")
    void suggestsNicknames() {
        assertThat(Players.suggestions(server, "Lilly_")).containsExactly("Lilly_Pad");
        assertThat(Players.suggestions(server, "lilly")).contains("lillyyxoxo");
    }

    @Test
    @DisplayName("a vanished player is given away neither by name nor by nickname to somebody who may not see them")
    void vanishHidesNickname() {
        Vanish vanish = mock(Vanish.class);
        UUID viewer = UUID.randomUUID();
        when(vanish.canSee(viewer, ghost.getUniqueId())).thenReturn(false);
        when(vanish.canSee(viewer, lilly.getUniqueId())).thenReturn(true);

        assertThat(Players.suggestions(server, "Boo", vanish, viewer)).isEmpty();
        assertThat(Players.suggestions(server, "Gh", vanish, viewer)).isEmpty();
        assertThat(Players.suggestions(server, "Lilly_", vanish, viewer)).containsExactly("Lilly_Pad");
    }
}
