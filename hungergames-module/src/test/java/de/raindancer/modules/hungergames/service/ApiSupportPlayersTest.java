package de.raindancer.modules.hungergames.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** The HTTP API names players by account: online, offline-but-seen, never by a stranger's nickname. */
class ApiSupportPlayersTest {

    @Test
    @DisplayName("an offline player the server has seen resolves by name; a stranger needs the UUID")
    void offlineResolves() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Server server = mock(Server.class);
            bukkit.when(Bukkit::getServer).thenReturn(server);
            UUID id = UUID.randomUUID();
            OfflinePlayer zed = mock(OfflinePlayer.class);
            when(zed.getUniqueId()).thenReturn(id);
            when(server.getOfflinePlayerIfCached("Zed")).thenReturn(zed);
            GameSession session = mock(GameSession.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
            ApiSupport support = new ApiSupport(session, mock(LogChannel.class), mock(SpectatorService.class),
                    HungerGamesSettings.DEFAULTS);

            assertThat(support.resolveName("Zed")).isEqualTo(id);
            assertThatThrownBy(() -> support.resolveName("Stranger")).isInstanceOf(ApiConflictException.class);
        }
    }

    @Test
    @DisplayName("an online player is found by exact name")
    void onlineByName() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Server server = mock(Server.class);
            bukkit.when(Bukkit::getServer).thenReturn(server);
            Player ann = mock(Player.class);
            when(server.getPlayerExact("Ann")).thenReturn(ann);
            when(ann.isOnline()).thenReturn(true);
            ApiSupport support = new ApiSupport(null, mock(LogChannel.class), mock(SpectatorService.class),
                    HungerGamesSettings.DEFAULTS);

            assertThat(support.findOnlinePlayer("Ann")).isSameAs(ann);
        }
    }

    @Test
    @DisplayName("a nickname or a selector never resolves through the API")
    void nicknamesDoNotAnswer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var database = de.raindancer.core.data.sql.Database.open(dir.resolve("core.db"),
                de.raindancer.core.data.sql.CoreSchema.CORE, () -> false);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            var nicknames = new de.raindancer.core.ui.identity.Nicknames(database);
            de.raindancer.core.platform.command.PlayerTargets.useNicknames(nicknames);
            Server server = mock(Server.class);
            bukkit.when(Bukkit::getServer).thenReturn(server);
            Player ann = mock(Player.class);
            UUID id = UUID.randomUUID();
            when(ann.getUniqueId()).thenReturn(id);
            when(ann.isOnline()).thenReturn(true);
            when(server.getPlayer(id)).thenReturn(ann);
            nicknames.remember(id, "Annie");
            GameSession session = mock(GameSession.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
            ApiSupport support = new ApiSupport(session, mock(LogChannel.class), mock(SpectatorService.class),
                    HungerGamesSettings.DEFAULTS);

            assertThat(support.findOnlinePlayer("Annie")).isNull();
            assertThatThrownBy(() -> support.resolveName("Annie")).isInstanceOf(ApiConflictException.class);
            assertThatThrownBy(() -> support.resolveName("@a")).isInstanceOf(ApiConflictException.class);
        } finally {
            de.raindancer.core.platform.command.PlayerTargets.useNicknames(null);
            database.close();
        }
    }
}
